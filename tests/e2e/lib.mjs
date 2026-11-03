// 端到端测试的公共工具：HTTP 调用、断言、直连测试库查数据。
// 配置全部走环境变量，默认值适配 tests/e2e/run.sh。
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

export const BASE = process.env.E2E_BASE || 'http://127.0.0.1:18091'
export const DB = process.env.E2E_DB || 'xc_e2e_test'
export const MYSQL = process.env.E2E_MYSQL || 'mysql'
export const MYSQL_USER = process.env.E2E_MYSQL_USER || 'root'
export const DEPLOY_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../deploy')

let pass = 0
const fails = []

export function ok(name, cond, extra = '') {
  if (cond) {
    pass++
    console.log('  PASS', name)
  } else {
    fails.push(name)
    console.log('  FAIL', name, extra)
  }
}

export async function call(token, method, url, body) {
  const res = await fetch(BASE + url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  return res.json().catch(() => ({ code: -1, msg: 'non-json ' + res.status }))
}

// 返回原始 Response，用于检查响应头 / 二进制内容（导出文件、附件）
export async function raw(token, method, url, body, headers = {}) {
  return fetch(BASE + url, {
    method,
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...headers
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
}

export async function login(username, password) {
  const j = await call(null, 'POST', '/login', { username, password, code: '', uuid: '' })
  return j.token || null
}

export const good = (j) => j && j.code === 200
export const denied = (j) => j && j.code !== 200

export function sql(text) {
  const r = spawnSync(MYSQL, ['-u' + MYSQL_USER, '--default-character-set=utf8mb4', DB, '-N', '-e', text], { encoding: 'utf8' })
  if (r.status !== 0) throw new Error('mysql: ' + r.stderr)
  return r.stdout.trim()
}

export function sqlFile(content) {
  return spawnSync(MYSQL, ['-u' + MYSQL_USER, '--default-character-set=utf8mb4', DB], { input: content, encoding: 'utf8' })
}

export function section(title) {
  console.log('== ' + title + ' ==')
}

export function finish() {
  console.log(`\n结果: ${pass} 通过, ${fails.length} 失败`)
  if (fails.length) {
    console.log('失败项:\n - ' + fails.join('\n - '))
    process.exit(1)
  }
}
