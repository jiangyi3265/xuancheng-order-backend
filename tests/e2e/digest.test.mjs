// 每日汇总提醒端到端测试：GET /jiedan/push/digest 预览每天 9:05 会推给每个人的汇总（只看不发）。
// 用自己建的账号（dg1/dg2/dgc1），可以在 flow.test.mjs 之后跑，也可以单独对新库跑。
// 库里已有别的测试留下的数据：老板那条汇总先取基线，再断言"新增了多少"；员工是新账号，断言整段文字。
// 定时任务本身（9:05 调 run()）不在这里触发：测试环境没配 SendKey，推送本来就发不出去。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ok, call, login, good, denied, sql, section, finish } from './lib.mjs'

const num = (v) => Number(v)
const pad = (n) => String(n).padStart(2, '0')
const day = (offset) => {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
const TODAY = day(0)
const YESTERDAY = day(-1)
const TOMORROW = day(1)

const post = (t, p, body = {}) => call(t, 'POST', p, body)
const setStatus = (t, id, status) => call(t, 'PUT', '/jiedan/order/status', { id, status })
const editOrder = (t, body) => call(t, 'PUT', '/jiedan/order', body)
const request = (t, body) => call(t, 'POST', '/jiedan/approval', body)

// 预览
const digests = async (t) => {
  const r = await call(t, 'GET', '/jiedan/push/digest')
  return good(r) && Array.isArray(r.data) ? r.data : []
}
let boss = null
const digestOf = async (id) => (await digests(boss)).find((d) => d.memberId === id)
const bodyOf = async (id) => (await digestOf(id))?.body ?? ''

// 把老板那条汇总拆成数字，没有的行就是 0
function stat(body) {
  const n = (re, i = 1) => {
    const m = body.match(re)
    return m ? num(m[i]) : 0
  }
  return {
    body,
    appr: n(/(\d+) 条申请等你批准/),
    hasWait: /条申请等你批准（最久的(已等 \d+ 天|等了不到 1 天)）/.test(body),
    wait: n(/最久的已等 (\d+) 天/),
    toAccept: n(/(\d+) 个项目等员工接收/),
    review: n(/(\d+) 个项目待验收/),
    overdue: n(/(\d+) 个项目已逾期/),
    owed: n(/(\d+) 个已完成的项目还没收齐款/),
    owedSum: n(/共欠 ¥(\d+(?:\.\d+)?)/),
    noDl: n(/(\d+) 个进行中的项目没填截止日期/)
  }
}
let bossId = null
const bossNow = async () => stat(await bodyOf(bossId))
// 比较两次老板汇总的差值（只列出非 0 的项），用来断言"只多了这一项"
const keys = ['appr', 'toAccept', 'review', 'overdue', 'owed', 'noDl']
// 统一成"按名字排序的 JSON"，这样 only({ review: 1, overdue: 1 }) 和 diff 的结果可以直接比较
const canon = (o) => JSON.stringify(Object.fromEntries(Object.entries(o).sort(([x], [y]) => (x < y ? -1 : 1))))
const diff = (a, b) => {
  const out = {}
  for (const k of keys) if (b[k] - a[k] !== 0) out[k] = b[k] - a[k]
  if (Math.abs(b.owedSum - a.owedSum) > 0.005) out.owedSum = Math.round((b.owedSum - a.owedSum) * 100) / 100
  return canon(out)
}
const same = (a, b) => diff(a, b) === '{}'
const only = (obj) => canon(obj)

section('A 准备账号')
boss = await login('admin', 'admin123')
ok('admin 登录', !!boss)
ok('创建员工 dg1(小钱)', good(await post(boss, '/jiedan/staff', { userName: 'dg1', nickName: '小钱', password: 'dg1pass' })))
ok('创建员工 dg2(小孙)', good(await post(boss, '/jiedan/staff', { userName: 'dg2', nickName: '小孙', password: 'dg2pass' })))
const staff = (await call(boss, 'GET', '/jiedan/staff/list')).data
const dg1 = staff.find((x) => x.userName === 'dg1').id
const dg2 = staff.find((x) => x.userName === 'dg2').id
bossId = staff.find((x) => x.role === 'boss').id
let t1 = await login('dg1', 'dg1pass')
let t2 = await login('dg2', 'dg2pass')
ok('两个员工都能登录', !!t1 && !!t2)
ok('创建客户 dgc1', good(await post(boss, '/jiedan/customer', { userName: 'dgc1', nickName: '汇总客', password: 'dgc1pass' })))
const cust = await login('dgc1', 'dgc1pass')
ok('客户登录', !!cust)
const custId = num(sql("select user_id from sys_user where user_name='dgc1'"))

section('B 没有要说的人不会收到汇总')
ok('刚建的员工 dg1 没有汇总', !(await digestOf(dg1)))
ok('刚建的员工 dg2 没有汇总', !(await digestOf(dg2)))
ok('客户永远没有汇总', !(await digestOf(custId)))
const all0 = await digests(boss)
ok('每条汇总的字段齐全，标题统一', all0.every((d) => typeof d.memberId === 'number' && d.name && ['boss', 'employee'].includes(d.role)
  && d.title === '📋 今日汇总' && typeof d.body === 'string' && d.body.length > 0), JSON.stringify(all0))
ok('汇总里每一行都不是空行', all0.every((d) => d.body.split('\n').every((l) => l.trim().length > 0)))
ok('所有老板收到的汇总内容相同', new Set(all0.filter((d) => d.role === 'boss').map((d) => d.body)).size <= 1)
const b0 = await bossNow()
console.log('  基线(老板)：', b0.body.replace(/\n/g, ' / ') || '（无）')

section('C 派给员工、等他接收')
let r = await post(boss, '/jiedan/order', { title: 'DG-等接收', customer: '甲', amount: 0, ownerId: dg1 })
ok('老板建单派给 dg1', good(r) && r.data.assignState === 'pending', JSON.stringify(r))
const oA = r.data.id
let d = await digestOf(dg1)
ok('员工：出现“1 个项目等你接收”', d?.body === '1 个项目等你接收', JSON.stringify(d))
ok('员工汇总的姓名和角色', d?.name === '小钱' && d?.role === 'employee' && d?.title === '📋 今日汇总', JSON.stringify(d))
ok('老板：只多了一个“等员工接收”', diff(b0, await bossNow()) === only({ toAccept: 1 }), diff(b0, await bossNow()))
ok('另一个员工 dg2 不受影响', !(await digestOf(dg2)))
ok('老板的汇总角色是 boss', (await digestOf(bossId))?.role === 'boss')

section('D 接收之后：没填截止日期 / 截止日期今天不算逾期 / 已逾期')
ok('dg1 接收', good(await post(t1, `/jiedan/order/${oA}/accept`)))
ok('接收后员工没有汇总（待确认的项目没有可说的）', !(await digestOf(dg1)))
ok('接收后老板的汇总回到基线', same(b0, await bossNow()), diff(b0, await bossNow()))
ok('dg1 开始做', good(await setStatus(t1, oA, 'doing')))
ok('员工：进行中没填截止日期', (await bodyOf(dg1)) === '1 个进行中的项目没填截止日期', await bodyOf(dg1))
ok('老板：进行中没填截止日期 +1', diff(b0, await bossNow()) === only({ noDl: 1 }), diff(b0, await bossNow()))
ok('员工填上明天的截止日期', good(await editOrder(t1, { id: oA, deadline: TOMORROW })))
ok('填了截止日期就没有汇总了', !(await digestOf(dg1)) && same(b0, await bossNow()))
ok('截止日期是今天：不算逾期', good(await editOrder(t1, { id: oA, deadline: TODAY })) && !(await digestOf(dg1)) && same(b0, await bossNow()))
ok('把截止日期清空：又算“没填”', good(await editOrder(t1, { id: oA, deadline: '' })) && (await bodyOf(dg1)) === '1 个进行中的项目没填截止日期')
ok('截止日期改成昨天：已逾期（不再算没填）', good(await editOrder(t1, { id: oA, deadline: YESTERDAY })) && (await bodyOf(dg1)) === '1 个项目已逾期', await bodyOf(dg1))
ok('老板：已逾期 +1', diff(b0, await bossNow()) === only({ overdue: 1 }), diff(b0, await bossNow()))
ok('提交验收：仍然逾期，老板那边多一个“待验收”', good(await setStatus(t1, oA, 'review')) && (await bodyOf(dg1)) === '1 个项目已逾期'
  && diff(b0, await bossNow()) === only({ overdue: 1, review: 1 }), diff(b0, await bossNow()))
ok('老板验收通过：员工和老板都回到基线', good(await setStatus(boss, oA, 'done')) && !(await digestOf(dg1)) && same(b0, await bossNow()), diff(b0, await bossNow()))

// 待确认状态的项目逾期也算；还没接收时同时有“等你接收”和“已逾期”
r = await post(boss, '/jiedan/order', { title: 'DG-待确认已逾期', customer: '乙', amount: 0, ownerId: dg1, deadline: YESTERDAY })
const oB = r.data.id
ok('还没接收且已逾期：两行都有', (await bodyOf(dg1)) === '1 个项目等你接收\n1 个项目已逾期', await bodyOf(dg1))
ok('老板：等员工接收 +1、已逾期 +1', diff(b0, await bossNow()) === only({ toAccept: 1, overdue: 1 }), diff(b0, await bossNow()))
ok('dg1 接收', good(await post(t1, `/jiedan/order/${oB}/accept`)))
ok('接收后只剩“已逾期”（状态是待确认，也算逾期）', (await bodyOf(dg1)) === '1 个项目已逾期', await bodyOf(dg1))
const bAfterB = await bossNow()
ok('老板：只剩已逾期 +1', diff(b0, bAfterB) === only({ overdue: 1 }), diff(b0, bAfterB))

section('E 已取消的项目哪里都不算')
const dBefore = await bodyOf(dg1)
const bBefore = await bossNow()
// 取消前先看一眼：同样的项目不取消的话，会让员工和老板的汇总都变
r = await post(boss, '/jiedan/order', { title: 'DG-取消前', customer: '丙', amount: 500, ownerId: dg1, deadline: YESTERDAY, status: 'doing' })
const oX = r.data.id
ok('取消之前它会被算进去（等接收 + 已逾期）', (await bodyOf(dg1)) === '1 个项目等你接收\n2 个项目已逾期', await bodyOf(dg1))
ok('老板那边也被算进去', diff(bBefore, await bossNow()) === only({ toAccept: 1, overdue: 1 }), diff(bBefore, await bossNow()))
ok('老板取消它', good(await setStatus(boss, oX, 'cancelled')))
ok('取消后员工的汇总回到原样', (await bodyOf(dg1)) === dBefore, await bodyOf(dg1))
ok('取消后老板的汇总回到原样', same(bBefore, await bossNow()), diff(bBefore, await bossNow()))
// 一开始就是已取消、带着各种“应该提醒”的特征：逾期、没填截止日期、有欠款
r = await post(boss, '/jiedan/order', { title: 'DG-直接取消', customer: '丁', amount: 777, ownerId: dg1, deadline: YESTERDAY, status: 'cancelled' })
ok('建一个一开始就是“已取消”的项目', good(r), JSON.stringify(r))
r = await post(boss, '/jiedan/order', { title: 'DG-取消无截止', customer: '丁', amount: 888, status: 'cancelled' })
ok('再建一个已取消、没截止日期、有报价的项目', good(r), JSON.stringify(r))
ok('已取消的项目不影响员工汇总', (await bodyOf(dg1)) === dBefore, await bodyOf(dg1))
ok('已取消的项目不影响老板汇总（含欠款）', same(bBefore, await bossNow()), diff(bBefore, await bossNow()))

section('F 已完成但没收齐款')
const bF0 = await bossNow()
r = await post(boss, '/jiedan/order', { title: 'DG-欠款整笔', customer: '戊', amount: 1000, status: 'done' })
ok('已完成、报价 1000、一分没收：欠 1000', diff(bF0, await bossNow()) === only({ owed: 1, owedSum: 1000 }), diff(bF0, await bossNow()))
r = await post(boss, '/jiedan/order', { title: 'DG-欠款一部分', customer: '戊', amount: 300, status: 'done' })
const oE = r.data.id
ok('收了 100', good(await post(boss, `/jiedan/order/${oE}/payment`, { amount: 100, payDate: TODAY, note: '定金' })))
ok('报价 300 收了 100：再欠 200，累计 2 个共 1200', diff(bF0, await bossNow()) === only({ owed: 2, owedSum: 1200 }), diff(bF0, await bossNow()))
r = await post(boss, '/jiedan/order', { title: 'DG-收齐了', customer: '戊', amount: 500, status: 'done' })
ok('收齐的不算', good(await post(boss, `/jiedan/order/${r.data.id}/payment`, { amount: 500, payDate: TODAY })) && diff(bF0, await bossNow()) === only({ owed: 2, owedSum: 1200 }))
r = await post(boss, '/jiedan/order', { title: 'DG-多收了', customer: '戊', amount: 200, status: 'done' })
ok('多收的不算', good(await post(boss, `/jiedan/order/${r.data.id}/payment`, { amount: 250, payDate: TODAY })) && diff(bF0, await bossNow()) === only({ owed: 2, owedSum: 1200 }))
r = await post(boss, '/jiedan/order', { title: 'DG-报价为0', customer: '戊', amount: 0, status: 'done' })
ok('已完成但报价是 0：不算欠款', good(r) && diff(bF0, await bossNow()) === only({ owed: 2, owedSum: 1200 }))
r = await post(boss, '/jiedan/order', { title: 'DG-没完成的有报价', customer: '戊', amount: 400, status: 'doing', deadline: TOMORROW })
ok('进行中的项目有报价也不算欠款', good(r) && diff(bF0, await bossNow()) === only({ owed: 2, owedSum: 1200 }))
const owedLine = (await bodyOf(bossId)).split('\n').find((l) => /还没收齐款/.test(l)) || ''
ok('欠款那一行的格式', /^\d+ 个已完成的项目还没收齐款，共欠 ¥\d+(\.\d{2})?$/.test(owedLine), owedLine)
ok('欠款不会算到员工头上', (await bodyOf(dg1)) === dBefore)
ok('收到欠款补上后，欠款减少', good(await post(boss, `/jiedan/order/${oE}/payment`, { amount: 200, payDate: TODAY, note: '尾款' })) && diff(bF0, await bossNow()) === only({ owed: 1, owedSum: 1000 }), diff(bF0, await bossNow()))
ok('老板自己名下进行中没填截止日期：只算在老板头上', good(await post(boss, '/jiedan/order', { title: 'DG-老板的单', customer: '己', amount: 10, status: 'doing' }))
  && diff(bF0, await bossNow()) === only({ owed: 1, owedSum: 1000, noDl: 1 }) && (await bodyOf(dg1)) === dBefore, diff(bF0, await bossNow()))

section('G 待批申请')
r = await post(boss, '/jiedan/order', { title: 'DG-申请用', customer: '庚', amount: 1000, ownerId: dg1, deadline: TOMORROW, status: 'doing' })
const oR = r.data.id
ok('dg1 接收', good(await post(t1, `/jiedan/order/${oR}/accept`)))
const dG0 = await bodyOf(dg1)
const bG0 = await bossNow()
ok('接收后员工汇总不变（有截止日期、不逾期）', dG0 === dBefore && dG0 === '1 个项目已逾期', dG0)
r = await request(t1, { orderId: oR, type: 'amount', reason: '客户砍价', payload: { amount: 800 } })
ok('dg1 提交改价申请', good(r), JSON.stringify(r))
const ap1 = r.data.id
ok('员工：还有 1 条申请在等老板批', (await bodyOf(dg1)) === dG0 + '\n你有 1 条申请还在等老板批', await bodyOf(dg1))
let bn = await bossNow()
ok('老板：申请 +1，带“最久的等了多久”', diff(bG0, bn) === only({ appr: 1 }) && bn.hasWait, bn.body)
ok('另一个员工 dg2 没有汇总', !(await digestOf(dg2)))
// 把这条申请改成 3 天前提交的（基于它自己的提交时间往前推，避开数据库和 Java 的时区差）
sql(`update jiedan_approval set create_time = create_time - interval 3 day where id=${ap1}`)
bn = await bossNow()
ok('老板：最久的已等 3 天（取最老的那条）', bn.wait === Math.max(bG0.wait, 3) && /条申请等你批准（最久的已等 \d+ 天）/.test(bn.body), bn.body)
ok('老板汇总里申请排在第一行', (await bodyOf(bossId)).split('\n')[0].includes('条申请等你批准'))
ok('员工的汇总里不显示等了几天', !/最久/.test(await bodyOf(dg1)))
r = await request(t1, { orderId: oR, type: 'cancel', reason: '先试试' })
const ap2 = r.data.id
ok('再提交一条申请：员工 2 条、老板 +2', (await bodyOf(dg1)) === dG0 + '\n你有 2 条申请还在等老板批' && diff(bG0, await bossNow()) === only({ appr: 2 }), await bodyOf(dg1))
ok('员工撤回一条：员工 1 条、老板 +1', good(await post(t1, `/jiedan/approval/${ap2}/withdraw`)) && (await bodyOf(dg1)) === dG0 + '\n你有 1 条申请还在等老板批' && diff(bG0, await bossNow()) === only({ appr: 1 }))
ok('老板驳回：员工和老板的汇总都回到原样', good(await post(boss, `/jiedan/approval/${ap1}/reject`, { note: '不行' })) && (await bodyOf(dg1)) === dG0 && same(bG0, await bossNow()), diff(bG0, await bossNow()))
ok('已处理的申请回到基线后“最久”也回到基线', (await bossNow()).wait === bG0.wait)
r = await request(t1, { orderId: oR, type: 'amount', reason: '又来', payload: { amount: 900 } })
ok('再来一条并被批准：同样不再算待批', good(r) && good(await post(boss, `/jiedan/approval/${r.data.id}/approve`)) && (await bodyOf(dg1)) === dG0 && same(bG0, await bossNow()))

section('H 员工之间互不串、停用的员工不打扰')
const dg1Body = await bodyOf(dg1)
ok('给 dg2 派一个新项目', good(await post(boss, '/jiedan/order', { title: 'DG-给小孙', customer: '辛', amount: 0, ownerId: dg2 })))
ok('dg2 只看到自己的', (await bodyOf(dg2)) === '1 个项目等你接收', await bodyOf(dg2))
ok('dg1 的汇总没变', (await bodyOf(dg1)) === dg1Body)
ok('停用 dg2', good(await call(boss, 'PUT', '/jiedan/staff/status', { userId: dg2, status: '1' })))
ok('停用的员工没有汇总', !(await digestOf(dg2)))
ok('其他人的汇总不变', (await bodyOf(dg1)) === dg1Body)
ok('重新启用 dg2', good(await call(boss, 'PUT', '/jiedan/staff/status', { userId: dg2, status: '0' })))
ok('启用后又有汇总', (await bodyOf(dg2)) === '1 个项目等你接收')
t2 = await login('dg2', 'dg2pass')
ok('dg2 能重新登录', !!t2)

section('I 谁能看预览')
ok('老板能看', good(await call(boss, 'GET', '/jiedan/push/digest')))
ok('员工不能看', denied(await call(t1, 'GET', '/jiedan/push/digest')))
ok('客户不能看', denied(await call(cust, 'GET', '/jiedan/push/digest')))
ok('没登录不能看', denied(await call(null, 'GET', '/jiedan/push/digest')))
ok('员工的拒绝不会泄露别人的汇总', !(await call(t1, 'GET', '/jiedan/push/digest')).data)
ok('原有的推送设置接口仍然可用', good(await call(t1, 'GET', '/jiedan/push/config')) && good(await call(boss, 'GET', '/jiedan/push/config')))
ok('手动截止提醒仍然只有老板能点', denied(await post(t1, '/jiedan/push/reminder')))

section('J 预览只看不改')
const snap = () => sql(`set session group_concat_max_len = 10000000; select concat_ws('|',
  (select count(*) from jiedan_order),
  (select ifnull(sum(version), 0) from jiedan_order),
  (select ifnull(md5(group_concat(concat_ws(',', id, status, ifnull(unread, ''), ifnull(assign_state, ''), received_amount) order by id)), '') from jiedan_order),
  (select count(*) from jiedan_approval),
  (select ifnull(md5(group_concat(concat_ws(',', id, status) order by id)), '') from jiedan_approval),
  (select count(*) from jiedan_timeline),
  (select count(*) from jiedan_payment),
  (select count(*) from jiedan_audit_log),
  (select count(*) from jiedan_setting))`)
const before = snap()
for (let i = 0; i < 3; i++) await digests(boss)
ok('多次预览后：订单/申请/时间线/收款/操作日志/设置都没变', snap() === before, before + ' -> ' + snap())
ok('订单数和申请数前后一致', sql('select count(*) from jiedan_order') === before.split('|')[0] && sql('select count(*) from jiedan_approval') === before.split('|')[3])

section('K 定时任务已登记')
const here = path.dirname(fileURLToPath(import.meta.url))
const cfg = fs.readFileSync(path.resolve(here, '../../ruoyi-admin/src/main/java/com/ruoyi/jiedan/config/JiedanScheduleConfig.java'), 'utf8')
ok('每天 9:05 触发每日汇总', /@Scheduled\(cron = "0 5 9 \* \* \?"\)\s+public void dailyDigest\(\)[\s\S]*?digestService\.run\(\)/.test(cfg))
ok('原来 9:00 的截止提醒还在', /@Scheduled\(cron = "0 0 9 \* \* \?"\)\s+public void deadlineReminder\(\)/.test(cfg))

finish()
