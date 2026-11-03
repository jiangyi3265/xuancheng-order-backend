// 上线前补丁包端到端测试：回收站、客户删除限制、操作日志、改密码、导出、变更戳、附件安全头。
// 用自己建的账号（hs1/hc1/hc2），可以在 permission.test.mjs 之后跑，也可以单独对新库跑。
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { BASE, ok, call, raw, login, good, denied, sql, section, finish } from './lib.mjs'

// 员工删除项目现在要先申请、老板批准（详见 flow.test.mjs）。这里只关心“进回收站”，所以走完整的申请 + 批准。
async function staffDelete(token, bossToken, id) {
  const r = await call(token, 'POST', '/jiedan/approval', { orderId: id, type: 'delete', reason: '测试删除' })
  if (!good(r)) return r
  return call(bossToken, 'POST', `/jiedan/approval/${r.data.id}/approve`)
}

const has = (rows, fn) => Array.isArray(rows) && rows.some(fn)

async function upload(token, name, bytes, type) {
  const fd = new FormData()
  fd.append('file', new Blob([bytes], { type }), name)
  const res = await fetch(BASE + '/jiedan/media/upload', {
    method: 'POST',
    headers: token ? { Authorization: 'Bearer ' + token } : {},
    body: fd
  })
  return res.json().catch(() => ({ code: -1 }))
}

async function auditRows(token, query = '') {
  const r = await call(token, 'GET', '/jiedan/audit/list?pageSize=100' + query)
  return good(r) ? r.data.rows : []
}

// 读 xlsx 里的全部文字（xlsx 就是 zip，文字在 sharedStrings.xml）
function xlsxText(buf) {
  const dir = mkdtempSync(path.join(tmpdir(), 'xc-xlsx-'))
  try {
    const f = path.join(dir, 'a.xlsx')
    writeFileSync(f, buf)
    const r = spawnSync('unzip', ['-p', f, 'xl/sharedStrings.xml'], { encoding: 'utf8' })
    return r.status === 0 ? r.stdout : null
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

section('A 准备账号和项目')
const boss = await login('admin', 'admin123')
ok('admin 登录', !!boss)
let r = await call(boss, 'POST', '/jiedan/staff', { userName: 'hs1', nickName: '小周', password: 'hs1pass' })
ok('创建下属 hs1', good(r), JSON.stringify(r))
const staffList = (await call(boss, 'GET', '/jiedan/staff/list')).data
const hs1 = staffList.find((x) => x.userName === 'hs1').id
const bossId = staffList.find((x) => x.role === 'boss').id
const t1 = await login('hs1', 'hs1pass')
ok('hs1 登录', !!t1)
ok('hs1 创建客户 hc1', good(await call(t1, 'POST', '/jiedan/customer', { userName: 'hc1', nickName: '一号客', password: 'hc1pass' })))
ok('boss 创建客户 hc2', good(await call(boss, 'POST', '/jiedan/customer', { userName: 'hc2', nickName: '二号客', password: 'hc2pass' })))
const c1 = await login('hc1', 'hc1pass')
const c2 = await login('hc2', 'hc2pass')
ok('两个客户登录', !!c1 && !!c2)

r = await call(t1, 'POST', '/jiedan/order', { title: 'H-下属的单', customer: '甲', amount: 100 })
const o1 = r.data.id
r = await call(boss, 'POST', '/jiedan/order', { title: 'H-老板的单', customer: '乙', amount: 200 })
const o2 = r.data.id
ok('建了两个项目', !!o1 && !!o2)

section('B 变更戳 /stamp')
const stampOf = async (token) => (await call(token, 'GET', '/jiedan/order/stamp')).data?.stamp
const s1 = await stampOf(t1)
const sb = await stampOf(boss)
ok('下属和总管理员都拿到变更戳', typeof s1 === 'string' && typeof sb === 'string', JSON.stringify([s1, sb]))
ok('下属的戳只统计自己的 1 个项目', s1.split('-')[0] === '1', s1)
await call(boss, 'PUT', '/jiedan/order/status', { id: o2, status: 'doing' })
ok('总管理员改自己的项目，下属的戳不变', (await stampOf(t1)) === s1)
ok('总管理员自己的戳变了', (await stampOf(boss)) !== sb)
await call(t1, 'PUT', '/jiedan/order/status', { id: o1, status: 'doing' })
ok('下属改自己的项目，戳变了', (await stampOf(t1)) !== s1)
ok('客户不能调用 stamp', denied(await call(c1, 'GET', '/jiedan/order/stamp')))
ok('无令牌不能调用 stamp', denied(await call(null, 'GET', '/jiedan/order/stamp')))

section('C 回收站：删除不是真删')
const stampBeforeDelete = await stampOf(t1)
await call(t1, 'POST', '/jiedan/order/progress', { orderId: o1, content: '一条进度', type: 'note' })
ok('hs1 直接删除被拒（要先申请）', denied(await call(t1, 'DELETE', '/jiedan/order/' + o1)))
ok('hs1 申请删除、老板批准后进回收站', good(await staffDelete(t1, boss, o1)))
ok('删除后详情读不到', denied(await call(t1, 'GET', '/jiedan/order/' + o1)))
ok('删除后版本接口读不到', denied(await call(t1, 'GET', `/jiedan/order/${o1}/version`)))
ok('删除后不在列表里', !has((await call(t1, 'GET', '/jiedan/order/list')).data, (o) => o.id === o1))
ok('删除后变更戳变了', (await stampOf(t1)) !== stampBeforeDelete)
ok('数据库里是软删除 del_flag=1', sql(`select del_flag from jiedan_order where id=${o1}`) === '1')
ok('留言/进度还在(没被物理删除)', Number(sql(`select count(*) from jiedan_timeline where order_id=${o1}`)) >= 2)
ok('删除人被记录', sql(`select delete_by from jiedan_order where id=${o1}`) === '尚琦凯')
ok('删除后不能再改', denied(await call(t1, 'PUT', '/jiedan/order', { id: o1, title: 'x' })))
ok('删除后不能再记事本', denied(await call(t1, 'GET', '/jiedan/order/notes/' + o1)))
let trash = await call(t1, 'GET', '/jiedan/order/trash')
ok('hs1 回收站里有它', good(trash) && has(trash.data, (x) => x.id === o1 && x.deleteBy === '尚琦凯'), JSON.stringify(trash))
ok('hs1 回收站里看不到别人的项目', !has(trash.data, (x) => x.id === o2))
ok('回收站的时间是「年-月-日 时:分」，不是带 T 的原始格式', /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(trash.data.find((x) => x.id === o1)?.deleteTime || ''), JSON.stringify(trash.data[0]))
ok('成员列表的项目数不含回收站', (await call(boss, 'GET', '/jiedan/staff/list')).data.find((x) => x.id === hs1).orderCount === 0)
ok('总管理员回收站也能看到', has((await call(boss, 'GET', '/jiedan/order/trash')).data, (x) => x.id === o1))

ok('总管理员删除自己的项目', good(await call(boss, 'DELETE', '/jiedan/order/' + o2)))
ok('hs1 回收站看不到老板删的项目', !has((await call(t1, 'GET', '/jiedan/order/trash')).data, (x) => x.id === o2))
ok('hs1 不能恢复老板的项目', denied(await call(t1, 'PUT', `/jiedan/order/${o2}/restore`)))
ok('hs1 不能彻底删除(仅总管理员)', denied(await call(t1, 'DELETE', `/jiedan/order/${o1}/purge`)))
ok('客户不能访问回收站', denied(await call(c1, 'GET', '/jiedan/order/trash')))
ok('客户不能恢复项目', denied(await call(c1, 'PUT', `/jiedan/order/${o1}/restore`)))

ok('hs1 恢复自己的项目', good(await call(t1, 'PUT', `/jiedan/order/${o1}/restore`)))
ok('恢复后回到列表', has((await call(t1, 'GET', '/jiedan/order/list')).data, (o) => o.id === o1))
ok('恢复后能读详情，进度还在', (await call(t1, 'GET', '/jiedan/order/' + o1)).data.timeline.some((t) => t.content === '一条进度'))
ok('恢复后删除标记已清除', sql(`select concat(del_flag, ifnull(delete_by,'-'))from jiedan_order where id=${o1}`) === '0-')
ok('重复恢复被拒', denied(await call(t1, 'PUT', `/jiedan/order/${o1}/restore`)))
ok('不在回收站的项目不能彻底删除', denied(await call(boss, 'DELETE', `/jiedan/order/${o1}/purge`)))

ok('总管理员彻底删除回收站里的项目', good(await call(boss, 'DELETE', `/jiedan/order/${o2}/purge`)))
ok('彻底删除后数据库里没有了', sql(`select count(*) from jiedan_order where id=${o2}`) === '0')
ok('彻底删除连带清掉进度', sql(`select count(*) from jiedan_timeline where order_id=${o2}`) === '0')

// 原负责人被停用后恢复：改由恢复人接手
r = await call(t1, 'POST', '/jiedan/order', { title: 'H-停用后恢复', customer: '丙' })
const o4 = r.data.id
await staffDelete(t1, boss, o4)
ok('停用 hs1', good(await call(boss, 'PUT', '/jiedan/staff/status', { userId: hs1, status: '1' })))
ok('总管理员恢复，原负责人不可用 -> 改派给总管理员', good(await call(boss, 'PUT', `/jiedan/order/${o4}/restore`)))
ok('负责人变成总管理员', sql(`select owner_id from jiedan_order where id=${o4}`) === String(bossId))
ok('重新启用 hs1', good(await call(boss, 'PUT', '/jiedan/staff/status', { userId: hs1, status: '0' })))
const t1b = await login('hs1', 'hs1pass')
ok('hs1 重新登录', !!t1b)

section('D 客户删除限制')
r = await call(c1, 'POST', '/jiedan/order/intake', { title: 'H-客户可撤回', requirement: 'r', contact: 'c' })
const k1 = r.data.id
r = await call(c1, 'POST', '/jiedan/order/intake', { title: 'H-已处理的', requirement: 'r', contact: 'c' })
const k2 = r.data.id
ok('客户提交两个需求，负责人是 hs1', good(r) && r.data.ownerId === hs1)
ok('客户可以撤回待确认的需求', good(await call(c1, 'DELETE', '/jiedan/portal/orders/' + k1)))
ok('撤回后客户列表里没有', !has((await call(c1, 'GET', '/jiedan/portal/orders')).data, (o) => o.id === k1))
ok('撤回的项目进了 hs1 的回收站，标明是客户删的', has((await call(t1b, 'GET', '/jiedan/order/trash')).data, (x) => x.id === k1 && /客户/.test(x.deleteBy)))
ok('数据库里仍是软删除', sql(`select del_flag from jiedan_order where id=${k1}`) === '1')
ok('hs1 可以恢复客户撤回的项目', good(await call(t1b, 'PUT', `/jiedan/order/${k1}/restore`)))
ok('恢复后客户又能看到', has((await call(c1, 'GET', '/jiedan/portal/orders')).data, (o) => o.id === k1))

await call(t1b, 'PUT', '/jiedan/order/status', { id: k2, status: 'doing' })
r = await call(c1, 'DELETE', '/jiedan/portal/orders/' + k2)
ok('团队已接手(进行中)后，客户不能自行删除', denied(r) && /处理中/.test(r.msg || ''), JSON.stringify(r))
ok('项目仍然完好', sql(`select del_flag from jiedan_order where id=${k2}`) === '0')
ok('客户不能删别的客户的项目', denied(await call(c2, 'DELETE', '/jiedan/portal/orders/' + k2)))
await call(boss, 'PUT', '/jiedan/order/status', { id: k2, status: 'done' })
ok('已完成的项目客户更不能删', denied(await call(c1, 'DELETE', '/jiedan/portal/orders/' + k2)))

section('E 操作日志')
// paid / receivedAmount 由收款流水推算，前端直接传来的值必须被忽略（见 solo.test.mjs）
await call(boss, 'PUT', '/jiedan/order', { id: k2, amount: 1500, deadline: '2030-01-01', paid: true, receivedAmount: 1500 })
await call(boss, 'PUT', '/jiedan/order', { id: k2, ownerId: bossId })
let rows = await auditRows(boss)
ok('总管理员能查日志', rows.length > 0)
const delRows = await auditRows(boss, '&action=order.delete')
ok('有「删除项目」记录，记录了操作人', has(delRows, (x) => x.targetId === o1 && x.actorName === '尚琦凯' && x.actorRole === 'boss'))
ok('有「恢复项目」记录', has(await auditRows(boss, '&action=order.restore'), (x) => x.targetId === o1))
ok('有「彻底删除」记录', has(await auditRows(boss, '&action=order.purge'), (x) => x.targetId === o2 && x.actorRole === 'boss'))
ok('客户撤回被记录为客户操作', has(delRows, (x) => x.targetId === k1 && x.actorRole === 'customer'))
const updRows = await auditRows(boss, '&action=order.update')
const upd = updRows.find((x) => x.targetId === k2 && /金额/.test(x.detail))
ok('改金额/截止留痕，写明旧值→新值', !!upd && /金额 0 → 1500/.test(upd.detail) && /截止 无 → 2030-01-01/.test(upd.detail), JSON.stringify(upd))
ok('前端直接传 paid / receivedAmount 被忽略', sql(`select concat(paid, '|', received_amount) from jiedan_order where id=${k2}`) === '0|0.00')
ok('转派留痕，写明前后负责人', has(updRows, (x) => x.targetId === k2 && /负责人 小周 → /.test(x.detail)))
ok('状态变更留痕', has(await auditRows(boss, '&action=order.status'), (x) => x.targetId === k2 && /进行中 → 已完成/.test(x.detail)))
ok('建单留痕', has(await auditRows(boss, '&action=order.create'), (x) => x.targetId === k1))
ok('建账号留痕', has(await auditRows(boss, '&action=staff.create'), (x) => /hs1/.test(x.targetName)) && has(await auditRows(boss, '&action=customer.create'), (x) => /hc1/.test(x.targetName)))
ok('停用账号留痕', has(await auditRows(boss, '&action=staff.status'), (x) => /停用/.test(x.detail)))
ok('日志带 IP', has(delRows, (x) => x.ip))
ok('日志时间是「年-月-日 时:分:秒」', delRows.every((x) => /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(x.createTime)), delRows[0]?.createTime)
const onlyDelete = await auditRows(boss, '&action=order.delete')
ok('按操作类型筛选', onlyDelete.length > 0 && onlyDelete.every((x) => x.action === 'order.delete'))
const byKeyword = await auditRows(boss, '&keyword=' + encodeURIComponent('H-客户可撤回'))
ok('按关键字筛选', byKeyword.length > 0 && byKeyword.every((x) => JSON.stringify(x).includes('H-客户可撤回')))
// 测试库的时间由 JDBC 按 GMT+8 写入（见 run.sh 的连接串），所以“今天”也要按东八区算；
// 直接用 UTC 日期的话，在本机（美东时区）每天中午以后会差一天。
const today = new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10)
ok('按日期筛选(今天)', (await auditRows(boss, `&beginTime=${today}&endTime=${today}`)).length > 0)
ok('按日期筛选(未来)为空', (await auditRows(boss, '&beginTime=2099-01-01')).length === 0)
const page1 = await call(boss, 'GET', '/jiedan/audit/list?pageNum=1&pageSize=3')
ok('分页：每页 3 条且有总数', good(page1) && page1.data.rows.length === 3 && page1.data.total > 3)
ok('恶意日期参数被忽略而不是报错', good(await call(boss, 'GET', "/jiedan/audit/list?beginTime=2020-01-01'%20or%201=1")))
ok('下属不能查日志', denied(await call(t1b, 'GET', '/jiedan/audit/list')))
ok('客户不能查日志', denied(await call(c1, 'GET', '/jiedan/audit/list')))
ok('无令牌不能查日志', denied(await call(null, 'GET', '/jiedan/audit/list')))

// 日志表出问题时，业务操作不能被连累
sql('rename table jiedan_audit_log to jiedan_audit_log_bak')
try {
  r = await call(boss, 'PUT', '/jiedan/order', { id: k2, amount: 1600 })
  ok('日志表不可用时，改金额依然成功', good(r) && Number(r.data.amount) === 1600, JSON.stringify(r))
} finally {
  sql('rename table jiedan_audit_log_bak to jiedan_audit_log')
}

section('F 修改密码')
const t1old = await login('hs1', 'hs1pass') // 第二台设备
ok('hs1 有两个会话', !!t1old && !!t1b)
ok('旧密码错误被拒', denied(await call(t1b, 'PUT', '/jiedan/account/password', { oldPassword: 'wrong', newPassword: 'newpass1' })))
ok('新密码太短被拒', denied(await call(t1b, 'PUT', '/jiedan/account/password', { oldPassword: 'hs1pass', newPassword: '123' })))
ok('新旧密码相同被拒', denied(await call(t1b, 'PUT', '/jiedan/account/password', { oldPassword: 'hs1pass', newPassword: 'hs1pass' })))
ok('无令牌不能改密码', denied(await call(null, 'PUT', '/jiedan/account/password', { oldPassword: 'a', newPassword: 'bbbbbb' })))
r = await call(t1b, 'PUT', '/jiedan/account/password', { oldPassword: 'hs1pass', newPassword: 'newpass1' })
ok('hs1 修改密码成功', good(r), JSON.stringify(r))
ok('当前设备保持登录', good(await call(t1b, 'GET', '/jiedan/order/list')))
ok('其他设备被踢下线', denied(await call(t1old, 'GET', '/jiedan/order/list')))
ok('旧密码不能再登录', (await login('hs1', 'hs1pass')) === null)
ok('新密码可以登录', !!(await login('hs1', 'newpass1')))
ok('改密码留痕(不含密码内容)', has(await auditRows(boss, '&action=account.password'), (x) => x.actorName === '小周' && !/newpass1|hs1pass/.test(JSON.stringify(x))))
r = await call(c1, 'PUT', '/jiedan/account/password', { oldPassword: 'hc1pass', newPassword: 'hc1new99' })
ok('客户也能改自己的密码', good(r), JSON.stringify(r))
ok('客户新密码可登录', !!(await login('hc1', 'hc1new99')))
ok('客户改密码后旧密码失效', (await login('hc1', 'hc1pass')) === null)

section('G 导出 Excel')
const tN = await login('hs1', 'newpass1')
r = await call(boss, 'POST', '/jiedan/order', { title: 'H-导出老板单', customer: '导出客户B', amount: 888.5 })
await call(tN, 'POST', '/jiedan/order', { title: 'H-导出下属单', customer: '导出客户S', amount: 99 })
let res = await raw(boss, 'GET', '/jiedan/order/export')
const bossBuf = Buffer.from(await res.arrayBuffer())
ok('总管理员导出：返回 xlsx 文件', res.status === 200 && /spreadsheetml/.test(res.headers.get('content-type') || '') && bossBuf.slice(0, 2).toString() === 'PK')
ok('文件名带日期并支持中文', /attachment; filename\*=UTF-8''/.test(res.headers.get('content-disposition') || '') && decodeURIComponent(res.headers.get('content-disposition')).includes('接单列表_'))
const bossText = xlsxText(bossBuf)
if (bossText === null) {
  console.log('  SKIP 未找到 unzip，跳过 xlsx 内容检查')
} else {
  ok('总管理员的导出含全部项目', bossText.includes('H-导出老板单') && bossText.includes('H-导出下属单'))
  res = await raw(tN, 'GET', '/jiedan/order/export')
  const sText = xlsxText(Buffer.from(await res.arrayBuffer()))
  ok('下属导出只含自己的项目', res.status === 200 && sText.includes('H-导出下属单') && !sText.includes('H-导出老板单'))
  ok('下属导出不含总管理员的任何项目', !sText.includes('导出客户B'))
  res = await raw(boss, 'GET', '/jiedan/order/export?keyword=' + encodeURIComponent('导出老板'))
  const kText = xlsxText(Buffer.from(await res.arrayBuffer()))
  ok('带关键字导出只含匹配项', kText.includes('H-导出老板单') && !kText.includes('H-导出下属单'))
  res = await raw(boss, 'GET', '/jiedan/order/export?status=cancelled&keyword=' + encodeURIComponent('H-导出'))
  const eText = xlsxText(Buffer.from(await res.arrayBuffer()))
  ok('筛选结果为空时仍是合法文件，只有表头', res.status === 200 && eText.includes('项目名') && !eText.includes('H-导出'))
  res = await raw(boss, 'GET', '/jiedan/order/export?overdue=1&keyword=' + encodeURIComponent('H-导出'))
  ok('逾期筛选(无逾期项目)导出为空', !xlsxText(Buffer.from(await res.arrayBuffer())).includes('H-导出'))
  res = await raw(boss, 'GET', '/jiedan/order/export?paid=1&keyword=' + encodeURIComponent('H-导出'))
  ok('已收款筛选(都未收款)导出为空', !xlsxText(Buffer.from(await res.arrayBuffer())).includes('H-导出'))
  res = await raw(boss, 'GET', '/jiedan/order/export?paid=0&keyword=' + encodeURIComponent('H-导出'))
  ok('未收款筛选能导出', xlsxText(Buffer.from(await res.arrayBuffer())).includes('H-导出老板单'))
  res = await raw(boss, 'GET', '/jiedan/order/export?createFrom=2099-01-01')
  ok('创建日期晚于今天的筛选导出为空', !xlsxText(Buffer.from(await res.arrayBuffer())).includes('H-'))
}
res = await raw(c1, 'GET', '/jiedan/order/export')
ok('客户不能导出', /json/.test(res.headers.get('content-type') || '') && denied(await res.json()))
res = await raw(null, 'GET', '/jiedan/order/export')
ok('无令牌不能导出', /json/.test(res.headers.get('content-type') || '') && denied(await res.json()))
ok('导出被记入操作日志', has(await auditRows(boss, '&action=order.export'), (x) => /导出 \d+ 条/.test(x.detail)))

section('H 附件安全头与上传限制')
const svg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')
r = await upload(boss, 'evil.svg', svg, 'image/svg+xml')
ok('上传 svg', good(r) && /\.svg$/.test(r.data.url), JSON.stringify(r))
res = await fetch(BASE + r.data.url)
ok('svg 匿名可读，类型正确', res.status === 200 && res.headers.get('content-type') === 'image/svg+xml')
ok('svg 带沙箱 CSP，直接打开不能执行脚本', /sandbox/.test(res.headers.get('content-security-policy') || '') && /default-src 'none'/.test(res.headers.get('content-security-policy') || ''))
ok('svg 带 nosniff', res.headers.get('x-content-type-options') === 'nosniff')

r = await upload(boss, 'page.html', Buffer.from('<script>alert(1)</script>'), 'text/html')
res = await fetch(BASE + r.data.url)
ok('html 附件按下载处理，不在浏览器里渲染', /octet-stream/.test(res.headers.get('content-type') || '') && /attachment/.test(res.headers.get('content-disposition') || ''))
ok('html 附件同样带沙箱 CSP', /sandbox/.test(res.headers.get('content-security-policy') || ''))

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')
r = await upload(boss, 'a.png', png, 'image/png')
res = await fetch(BASE + r.data.url)
ok('png 正常显示，不受 CSP 影响', res.status === 200 && res.headers.get('content-type') === 'image/png' && !res.headers.get('content-security-policy'))

const big = Buffer.alloc(12 * 1024 * 1024, 7) // 12MB：超过旧的 10MB 上限
r = await upload(boss, 'big.mp4', big, 'video/mp4')
ok('12MB 的视频可以上传(上限已与 50MB 对齐)', good(r), JSON.stringify(r).slice(0, 200))
ok('无令牌不能上传(匿名读取的放行规则不能连带放开上传)', denied(await upload(null, 'x.png', png, 'image/png')))
ok('乱写的令牌不能上传', denied(await upload('not-a-real-token', 'x.png', png, 'image/png')))
ok('客户可以上传', good(await upload(c2, 'c.png', png, 'image/png')))
ok('下属可以上传', good(await upload(tN, 's.png', png, 'image/png')))

section('I 安全：Druid 监控页不对外')
res = await fetch(BASE + '/druid/index.html')
const druidBody = await res.text()
ok('/druid 监控页已关闭', res.status === 404 || !/druid/i.test(druidBody), `${res.status} ${druidBody.slice(0, 80)}`)

finish()
