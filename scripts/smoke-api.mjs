import assert from 'node:assert/strict'

const base = (process.env.BLOG_API_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const username = process.env.BLOG_SMOKE_USERNAME || 'admin'
const password = process.env.BLOG_SMOKE_PASSWORD
if (!password) throw new Error('Set BLOG_SMOKE_PASSWORD before running this explicit integration check')

const prefix = `smoke-${Date.now()}`
const created = []
let token = ''

async function request(path, { method = 'GET', body, authenticated = false, authToken, status = 200 } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const authorization = authToken ?? (authenticated ? token : '')
  if (authorization) headers.Authorization = authorization
  const response = await fetch(base + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000)
  })
  const data = await response.json()
  assert.equal(response.status, status, `${method} ${path}: unexpected HTTP status`)
  if (status === 200) assert.ok(['200', '20000', '20001'].includes(String(data.code)), `${method} ${path}: expected successful result`)
  else assert.ok(!['200', '20000', '20001'].includes(String(data.code)), `${method} ${path}: failure must not use a success code`)
  return { response, data: data.data }
}

async function login() {
  const result = await request('/login', { method: 'POST', body: { username, password } })
  token = result.response.headers.get('authorization')
  assert.ok(token, 'Login must return an Authorization response header')
  assert.equal(result.data.username.toLowerCase(), username.toLowerCase())
  assert.ok(!Object.hasOwn(result.data, 'password'), 'Login must not expose a password hash')
  return result.data
}

try {
  await request('/blogs')
  await request('/me', { status: 401 })
  await request('/blog/edit', { method: 'POST', body: {}, status: 401 })
  const user = await login()
  const me = (await request('/me', { authenticated: true })).data
  assert.equal(me.id, user.id)
  assert.ok(!Object.hasOwn(me, 'password'), 'Identity endpoint must not expose a password hash')
  console.log('PASS anonymous access, protected writes, login and safe identity')

  const draftResult = await request('/blog/edit', {
    method: 'POST', authenticated: true,
    body: { title: prefix, description: 'Explicit API integration check', content: '# Draft\n\nInitial content' }
  })
  const draft = draftResult.data
  assert.ok(draft?.id, 'Save must return the saved article')
  created.push(draft.id)
  assert.equal(draft.status, 0, 'New articles without explicit status must remain drafts')
  assert.equal(draft.userId, user.id)
  await request(`/blog/${draft.id}`, { status: 404 })
  const mine = (await request('/my/blogs?currentPage=1&status=0', { authenticated: true })).data
  assert.ok(mine.records.some(blog => blog.id === draft.id), 'Draft must appear in the owner list')
  const publicDrafts = (await request('/blogs')).data.records
  assert.ok(!publicDrafts.some(blog => blog.id === draft.id), 'Draft must not appear in the public list')
  console.log('PASS draft defaults and owner-only visibility')

  await request('/blog/edit', {
    method: 'POST', authenticated: true,
    body: { id: draft.id, title: prefix, description: '', content: '# Published\n\nUpdated content', status: 1 }
  })
  const published = (await request(`/blog/${draft.id}`)).data
  assert.equal(published.status, 1)
  assert.equal(published.created, draft.created, 'Editing must preserve creation time')
  assert.equal(published.userId, user.id)
  const summaries = (await request('/blogs')).data.records
  const summary = summaries.find(blog => blog.id === draft.id)
  assert.ok(summary, 'Published article must appear in the public list')
  assert.ok(!Object.hasOwn(summary, 'content'), 'List responses must omit full Markdown content')
  await request('/blog/edit', {
    method: 'POST', authenticated: true,
    body: { id: draft.id, title: prefix, description: '', content: '# Published\n\nSecond update' }
  })
  assert.equal((await request(`/blog/${draft.id}`)).data.status, 1, 'Omitted status must preserve an existing publication')
  console.log('PASS publish, edit, stable metadata and compact public summaries')

  await request(`/blog/${draft.id}`, { method: 'DELETE', status: 401 })
  const expired = token
  await request('/logout', { method: 'POST', authenticated: true })
  token = ''
  await request('/me', { authToken: expired, status: 401 })
  console.log('PASS logout revokes the token on the server')
} finally {
  if (created.length) {
    await login()
    for (const id of created) {
      await request(`/blog/${id}`, { method: 'DELETE', authenticated: true })
      await request(`/blog/${id}`, { status: 404 })
      await request(`/blog/${id}`, { authenticated: true, status: 404 })
    }
    console.log(`PASS soft deletion; integration article IDs: ${created.join(', ')}`)
  }
  if (token) await request('/logout', { method: 'POST', authenticated: true })
}

console.log('API integration checks passed')
