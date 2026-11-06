import assert from 'node:assert/strict'

const site = (process.env.BLOG_SITE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '')
const origin = process.env.BLOG_SMOKE_ORIGIN || site
const get = (path, options = {}) => fetch(site + path, { signal: AbortSignal.timeout(15000), ...options })

const home = await get('/')
assert.equal(home.status, 200)
assert.match(home.headers.get('content-type'), /text\/html/)
assert.match(home.headers.get('cache-control'), /no-cache/)
const html = await home.text()
const asset = html.match(/src="(\/assets\/[^" ]+\.js)"/)?.[1]
assert.ok(asset, 'Home must load a built JavaScript asset')
const js = await get(asset)
assert.equal(js.status, 200)
assert.match(js.headers.get('cache-control'), /immutable/)
assert.equal((await get('/assets/missing-smoke-file.js')).status, 404)
const detailRoute = await get('/blog/123456789')
assert.equal(detailRoute.status, 200)
assert.equal(await detailRoute.text(), html, 'Direct history navigation must load the SPA')
assert.equal((await get('/api', { redirect: 'manual' })).status, 308)
const missingApi = await get('/api/missing-smoke-endpoint')
assert.equal(missingApi.status, 404)
assert.match(missingApi.headers.get('content-type'), /application\/json/)
console.log('PASS built assets, cache policy, history fallback and API boundary')

for (const path of ['/healthz', '/api/health/live', '/api/health/ready']) {
  const response = await get(path)
  assert.equal(response.status, 200, path)
  assert.deepEqual(await response.json(), { status: 'UP' })
}
const allowed = await get('/api/blog/edit', {
  method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'POST' }
})
assert.equal(allowed.status, 204)
assert.equal(allowed.headers.get('access-control-allow-origin'), origin)
const blocked = await get('/api/login', { method: 'POST', headers: { Origin: 'https://untrusted.invalid' } })
assert.equal(blocked.status, 403)
console.log('PASS web/API health, allowed browser origin and rejected foreign origin')

process.env.BLOG_API_URL = site + '/api'
process.env.BLOG_SMOKE_ORIGIN = origin
await import('./smoke-api.mjs')
