#!/bin/sh
# Run on a Linux Docker host. Uses disposable dependencies and always tears them down.
set -eu
cd "$(dirname "$0")"
project=${BLOG_SMOKE_PROJECT:-marker-hub-smoke-$(date +%Y%m%d%H%M%S)-$$}
case "$project" in marker-hub-smoke-*) ;; *) echo 'Use a unique marker-hub-smoke-* project name' >&2; exit 1 ;; esac
case "$project" in *[!a-z0-9-]*) echo 'Project name must contain lowercase letters, digits and hyphens' >&2; exit 1 ;; esac
for kind in container network volume; do
    flags=-q
    [ "$kind" != container ] || flags=-aq
    if [ -n "$(docker "$kind" ls "$flags" --filter "label=com.docker.compose.project=$project")" ]; then
        echo "Project $project already has resources; refusing to reuse it" >&2
        exit 1
    fi
done
if docker buildx inspect "$project" >/dev/null 2>&1; then
    echo 'Builder name already exists; refusing to reuse it' >&2
    exit 1
fi
export BLOG_BIND_IP=127.0.0.1
export BLOG_HTTP_PORT=${BLOG_HTTP_PORT:-18089}
export BLOG_ALLOWED_ORIGINS="http://127.0.0.1:$BLOG_HTTP_PORT,http://localhost:$BLOG_HTTP_PORT"
export BLOG_API_IMAGE="$project-api:test"
export BLOG_WEB_IMAGE="$project-web:test"
export COMPOSE_PARALLEL_LIMIT=${COMPOSE_PARALLEL_LIMIT:-1}
build_images=${BLOG_SMOKE_BUILD:-1}
case "$build_images" in 0|1) ;; *) echo 'BLOG_SMOKE_BUILD must be 0 or 1' >&2; exit 1 ;; esac
if [ "$build_images" = 0 ]; then
    docker image inspect "$BLOG_API_IMAGE" "$BLOG_WEB_IMAGE" >/dev/null
fi
builder_created=0
umask 077
env_file=$(mktemp)
admin_password=$(openssl rand -hex 20)
printf 'SMOKE_DB_PASSWORD=%s\nSMOKE_JWT_SECRET=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 64)" > "$env_file"
new_images=''
for image in mysql:8.4 redis:8-alpine node:24-alpine moby/buildkit:buildx-stable-1; do
    if ! docker image inspect "$image" >/dev/null 2>&1; then new_images="$new_images $image"; fi
done
compose() {
    docker compose --env-file "$env_file" -p "$project" -f compose.yaml -f compose.smoke.yaml "$@"
}
cleanup() {
    result=$?
    trap - EXIT HUP INT TERM
    set +e
    if [ "$result" != 0 ]; then compose logs --no-color --tail 100 >&2; fi
    if docker container inspect "$project-checks" >/dev/null 2>&1; then
        docker container rm -f "$project-checks"
    fi
    compose down --volumes --remove-orphans
    if [ "$builder_created" = 1 ]; then docker buildx rm "$project"; fi
    docker image rm "$BLOG_API_IMAGE" "$BLOG_WEB_IMAGE"
    for image in $new_images; do docker image rm "$image"; done
    rm -f "$env_file"
    for image in "$BLOG_API_IMAGE" "$BLOG_WEB_IMAGE"; do
        if docker image inspect "$image" >/dev/null 2>&1; then
            echo "Cleanup incomplete: test image $image remains" >&2
            result=1
        fi
    done
    if [ "$builder_created" = 1 ] && docker buildx inspect "$project" >/dev/null 2>&1; then
        echo "Cleanup incomplete: builder $project remains" >&2
        result=1
    fi
    for kind in container network volume; do
        flags=-q
        [ "$kind" != container ] || flags=-aq
        if [ -n "$(docker "$kind" ls "$flags" --filter "label=com.docker.compose.project=$project")" ]; then
            echo "Cleanup incomplete: $kind resources remain for $project" >&2
            result=1
        fi
    done
    echo "Cleanup finished for $project (exit $result)"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM
compose config --quiet
if [ "$build_images" = 1 ]; then
    docker buildx create --name "$project" --driver docker-container
    builder_created=1
    compose build --builder "$project"
fi
compose up -d --wait --wait-timeout 300 smoke-db smoke-redis
printf '%s\n' "$admin_password" | compose run --rm -T --no-deps -e BLOG_ADMIN_USERNAME=smokeadmin api --spring.profiles.active=bootstrap-admin
compose up -d --wait --wait-timeout 180
compose ps
docker run --rm --name "$project-checks" --label "com.docker.compose.project=$project" --network "${project}_default" \
    -e BLOG_SITE_URL=http://web:8080 \
    -e "BLOG_SMOKE_ORIGIN=http://127.0.0.1:$BLOG_HTTP_PORT" \
    -e BLOG_SMOKE_USERNAME=smokeadmin -e "BLOG_SMOKE_PASSWORD=$admin_password" \
    --mount "type=bind,src=$(cd ../scripts && pwd),dst=/checks,readonly" \
    node:24-alpine node /checks/smoke-deploy.mjs

ready() {
    curl --fail --silent --show-error --max-time 10 --retry 15 --retry-delay 2 --retry-all-errors \
        "http://127.0.0.1:$BLOG_HTTP_PORT/api/health/ready"
}
# Recreate API with a different container, without restarting Nginx.
compose up -d --no-deps --force-recreate --wait --wait-timeout 180 api
ready
echo 'PASS API replacement and Nginx upstream recovery'
data_counts() {
    compose exec -T smoke-db sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N --database=marker-hub -e "SELECT COUNT(*) FROM m_user; SELECT COUNT(*) FROM m_blog;"'
}
before_restart=$(data_counts)
for dependency in smoke-redis smoke-db; do
    compose stop "$dependency"
    status=$(curl --silent --show-error --max-time 15 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$BLOG_HTTP_PORT/api/health/ready")
    [ "$status" = 503 ]
    curl --fail --silent --max-time 5 "http://127.0.0.1:$BLOG_HTTP_PORT/api/health/live"
    compose start "$dependency"
    ready
    echo "PASS $dependency failure/readiness/recovery; liveness remains available"
done
[ "$(data_counts)" = "$before_restart" ]
echo 'PASS database records survive dependency restarts'
echo 'Deployment integration checks passed'
