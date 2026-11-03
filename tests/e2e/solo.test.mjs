// 记单收款版端到端测试：收款流水、已收/收清推算、权限范围、实收统计、导出、状态校验。
// （升级回填的检查在 run.sh 的升级演练模式里，因为它依赖测试开始前的旧数据。）
// 用自己建的账号（ss1/sc1），可以在 hardening.test.mjs 之后跑，也可以单独对新库跑。
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { BASE, ok, call, raw, login, good, denied, sql, section, finish } from './lib.mjs'

const has = (rows, fn) => Array.isArray(rows) && rows.some(fn)
const num = (v) => Number(v)

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

// 日期都用本机时间生成（后端和测试同在一台机器、同一时区）
const pad = (n) => String(n).padStart(2, '0')
const now = new Date()
const ym = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
const THIS = ym(now)
const D_THIS = `${THIS}-01`
const D_PREV = `${ym(new Date(now.getFullYear(), now.getMonth() - 1, 1))}-15`
const D_OLD = `${ym(new Date(now.getFullYear(), now.getMonth() - 8, 1))}-10` // 超出最近 6 个月

const pay = (token, id, body) => call(token, 'POST', `/jiedan/order/${id}/payment`, body)
const detail = async (token, id) => (await call(token, 'GET', '/jiedan/order/' + id)).data
const listRow = async (token, id) => (await call(token, 'GET', '/jiedan/order/list')).data.find((o) => o.id === id)
const stampOf = async (token) => (await call(token, 'GET', '/jiedan/order/stamp')).data?.stamp
const auditRows = async (token, query = '') => {
  const r = await call(token, 'GET', '/jiedan/audit/list?pageSize=100' + query)
  return good(r) ? r.data.rows : []
}

section('A 准备账号和项目')
const boss = await login('admin', 'admin123')
ok('admin 登录', !!boss)
let r = await call(boss, 'POST', '/jiedan/staff', { userName: 'ss1', nickName: '小林', password: 'ss1pass' })
ok('创建下属 ss1', good(r), JSON.stringify(r))
const t1 = await login('ss1', 'ss1pass')
ok('ss1 登录', !!t1)
ok('创建客户 sc1', good(await call(boss, 'POST', '/jiedan/customer', { userName: 'sc1', nickName: '收款客', password: 'sc1pass' })))
const c1 = await login('sc1', 'sc1pass')
ok('sc1 登录', !!c1)

r = await call(boss, 'POST', '/jiedan/order', { title: 'S-老板的单', customer: '甲', amount: 1000, contact: 'wxid_solo9', requirement: '需要一个独一无二的暗号蓝莓派' })
const oB = r.data.id
r = await call(t1, 'POST', '/jiedan/order', { title: 'S-下属的单', customer: '乙', amount: 500 })
const oS = r.data.id
r = await call(boss, 'POST', '/jiedan/order', { title: 'S-客户的单', customer: '收款客', amount: 300, customerAccount: 'sc1' })
const oC = r.data.id
ok('建了三个项目', !!oB && !!oS && !!oC)
ok('新项目已收 0、未收清', num((await detail(boss, oB)).receivedAmount) === 0 && (await detail(boss, oB)).paid === false)
ok('新项目收款流水为空数组', Array.isArray((await detail(boss, oB)).payments) && (await detail(boss, oB)).payments.length === 0)

// 升级演练模式下，库里已有“升级前标记收款”补录的流水，所以统计一律和测试前的基线比增量
const base = (await call(boss, 'GET', '/jiedan/order/income')).data
ok('测试前取到统计基线', !!base && Array.isArray(base.months) && base.months.length === 6, JSON.stringify(base))

section('B 记收款与自动推算')
const stampBefore = await stampOf(boss)
r = await pay(boss, oB, { amount: 300, payDate: D_THIS, note: '定金' })
ok('记一笔定金 300', good(r), JSON.stringify(r))
ok('返回更新后的详情：已收 300、未收清', num(r.data.receivedAmount) === 300 && r.data.paid === false)
ok('流水带金额/日期/备注/记录人', r.data.payments.length === 1 && num(r.data.payments[0].amount) === 300
  && r.data.payments[0].payDate === D_THIS && r.data.payments[0].note === '定金' && r.data.payments[0].createdBy === '尚琦凯',
  JSON.stringify(r.data.payments))
ok('记收款后变更戳变了(其他设备会刷新)', (await stampOf(boss)) !== stampBefore)
ok('列表里的已收金额同步', num((await listRow(boss, oB)).receivedAmount) === 300)
ok('数据库里已收金额=流水合计', sql(`select received_amount from jiedan_order where id=${oB}`) === '300.00')

r = await pay(boss, oB, { amount: '700.00', payDate: D_PREV, note: '尾款' })
ok('再记一笔尾款 700(字符串金额)', good(r) && num(r.data.receivedAmount) === 1000 && r.data.paid === true, JSON.stringify(r))
ok('流水按日期倒序', r.data.payments.map((x) => x.payDate).join() === [D_THIS, D_PREV].join(), JSON.stringify(r.data.payments))
ok('列表里 paid=true(已收清)', (await listRow(boss, oB)).paid === true)

r = await pay(boss, oB, { amount: 50, payDate: D_THIS, note: '多收了' })
ok('多收一笔允许，已收 1050', good(r) && num(r.data.receivedAmount) === 1050 && r.data.paid === true)
const extra = r.data.payments.find((x) => x.note === '多收了').id
r = await call(boss, 'DELETE', '/jiedan/order/payment/' + extra)
ok('删掉记错的一笔，回到 1000', good(r) && num(r.data.receivedAmount) === 1000 && r.data.payments.length === 2, JSON.stringify(r))

const first = (await detail(boss, oB)).payments.find((x) => x.note === '尾款').id
r = await call(boss, 'DELETE', '/jiedan/order/payment/' + first)
ok('删掉尾款 -> 已收 300、不再算收清', good(r) && num(r.data.receivedAmount) === 300 && r.data.paid === false)
await pay(boss, oB, { amount: 700, payDate: D_PREV, note: '尾款' })
ok('补回尾款 -> 收清', (await detail(boss, oB)).paid === true)

section('C 改报价后的收清状态')
r = await call(boss, 'PUT', '/jiedan/order', { id: oB, amount: 2000 })
ok('报价改成 2000：已收 1000 不变，不再算收清', good(r) && num(r.data.receivedAmount) === 1000 && r.data.paid === false, JSON.stringify(r.data))
r = await call(boss, 'PUT', '/jiedan/order', { id: oB, amount: 800 })
ok('报价改成 800：已收 1000 >= 800，算收清', good(r) && r.data.paid === true)
r = await call(boss, 'PUT', '/jiedan/order', { id: oB, amount: 1000 })
ok('改回 1000，仍是收清', r.data.paid === true && num(r.data.receivedAmount) === 1000)
ok('改报价留痕', has(await auditRows(boss, '&action=order.update'), (x) => x.targetId === oB && /金额 1000 → 2000/.test(x.detail)))

section('D 输入校验')
const bad = async (name, body) => ok(name, denied(await pay(boss, oB, body)))
await bad('金额 0 被拒', { amount: 0 })
await bad('负数被拒', { amount: -5 })
await bad('非数字被拒', { amount: 'abc' })
await bad('缺金额被拒', { note: 'x' })
await bad('金额过大被拒', { amount: 1000000000 })
await bad('日期格式错被拒', { amount: 10, payDate: '2026/13/45' })
await bad('将来的日期被拒', { amount: 10, payDate: '2099-01-01' })
await bad('备注太长被拒', { amount: 10, note: 'x'.repeat(201) })
r = await pay(boss, oB, { amount: 0.005 })
ok('金额四舍五入到分，0.005 -> 0.01 可记', good(r) && num(r.data.payments[0].amount) === 0.01, JSON.stringify(r))
await call(boss, 'DELETE', '/jiedan/order/payment/' + r.data.payments[0].id)
r = await pay(boss, oB, { amount: 12.34 })
ok('不传日期默认今天', good(r) && /^\d{4}-\d{2}-\d{2}$/.test(r.data.payments[0].payDate), JSON.stringify(r.data.payments[0]))
await call(boss, 'DELETE', '/jiedan/order/payment/' + r.data.payments[0].id)
ok('删除不存在的收款被拒', denied(await call(boss, 'DELETE', '/jiedan/order/payment/99999999')))
ok('给不存在的项目记收款被拒', denied(await pay(boss, 99999999, { amount: 10 })))
ok('校验失败没有留下脏数据', num((await detail(boss, oB)).receivedAmount) === 1000)

section('E 权限范围')
ok('下属不能给总管理员的项目记收款', denied(await pay(t1, oB, { amount: 10 })))
const bossPay = (await detail(boss, oB)).payments[0].id
ok('下属不能删总管理员项目的收款', denied(await call(t1, 'DELETE', '/jiedan/order/payment/' + bossPay)))
ok('被拒后收款流水没变', (await detail(boss, oB)).payments.length === 2)
r = await pay(t1, oS, { amount: 200, payDate: D_THIS, note: '下属收的' })
ok('下属能给自己的项目记收款', good(r) && num(r.data.receivedAmount) === 200)
ok('总管理员也能给下属的项目记收款', good(await pay(boss, oS, { amount: 100, payDate: D_PREV })))
ok('下属的详情能看到收款明细', (await detail(t1, oS)).payments.length === 2)
ok('客户不能记收款', denied(await pay(c1, oC, { amount: 10 })))
ok('客户不能删收款', denied(await call(c1, 'DELETE', '/jiedan/order/payment/' + bossPay)))
ok('无令牌不能记收款', denied(await pay(null, oC, { amount: 10 })))
ok('无令牌不能删收款', denied(await call(null, 'DELETE', '/jiedan/order/payment/' + bossPay)))
ok('客户不能看实收统计', denied(await call(c1, 'GET', '/jiedan/order/income')))
ok('无令牌不能看实收统计', denied(await call(null, 'GET', '/jiedan/order/income')))

await pay(boss, oC, { amount: 120, payDate: D_THIS, note: '客户的单收了一部分' })
const portalList = await call(c1, 'GET', '/jiedan/portal/orders')
const portalRow = portalList.data.find((o) => o.id === oC)
ok('客户在自己的项目列表里看不到收款明细', !!portalRow && !('payments' in portalRow) && !('receivedAmount' in portalRow), JSON.stringify(portalRow))
const portalOne = await call(c1, 'GET', '/jiedan/portal/orders/' + oC)
ok('客户在项目详情里也看不到收款明细', good(portalOne) && !('payments' in portalOne.data) && !('receivedAmount' in portalOne.data), JSON.stringify(Object.keys(portalOne.data || {})))

section('F 实收统计 /income')
// 目前的流水：oB 300(本月)+700(上月) ；oS 200(本月)+100(上月) ；oC 120(本月)
const incB = (await call(boss, 'GET', '/jiedan/order/income')).data
ok('总管理员本月实收增加 300+200+120', num(incB.thisMonth) === num(base.thisMonth) + 620, JSON.stringify(incB))
ok('累计实收增加 1000+300+120', num(incB.total) === num(base.total) + 1420, JSON.stringify(incB))
ok('最近 6 个月逐月，最后一个是本月', incB.months.length === 6 && incB.months[5].month === THIS && num(incB.months[5].amount) === num(base.months[5].amount) + 620)
ok('上月实收增加 700+100', num(incB.months[4].amount) === num(base.months[4].amount) + 800, JSON.stringify(incB.months))
const incS = (await call(t1, 'GET', '/jiedan/order/income')).data
ok('下属只统计自己的项目：本月 200、累计 300', num(incS.thisMonth) === 200 && num(incS.total) === 300, JSON.stringify(incS))

await pay(boss, oB, { amount: 9, payDate: D_OLD, note: '很久以前' })
const incOld = (await call(boss, 'GET', '/jiedan/order/income')).data
ok('6 个月以前的收款计入累计、不计入逐月', num(incOld.total) === num(base.total) + 1429 && incOld.months.every((m) => m.month >= incB.months[0].month)
  && JSON.stringify(incOld.months) === JSON.stringify(incB.months))
const oldId = (await detail(boss, oB)).payments.find((x) => x.note === '很久以前').id
await call(boss, 'DELETE', '/jiedan/order/payment/' + oldId)

await call(t1, 'DELETE', '/jiedan/order/' + oS)
ok('下属的项目进回收站后，不再计入统计', num((await call(boss, 'GET', '/jiedan/order/income')).data.total) === num(base.total) + 1420 - 300)
await call(t1, 'PUT', `/jiedan/order/${oS}/restore`)
ok('恢复后收款流水还在，统计恢复', num((await call(boss, 'GET', '/jiedan/order/income')).data.total) === num(base.total) + 1420)

await call(boss, 'PUT', '/jiedan/order/status', { id: oC, status: 'cancelled' })
ok('已取消项目收过的钱照算', num((await call(boss, 'GET', '/jiedan/order/income')).data.total) === num(base.total) + 1420)

section('G 导出与筛选')
r = await call(boss, 'POST', '/jiedan/order', { title: 'S-一分没收', customer: '丙', amount: 400 })
r = await call(boss, 'POST', '/jiedan/order', { title: 'S-没报价', customer: '丁', amount: 0 })
const res0 = await raw(boss, 'GET', '/jiedan/order/export?keyword=' + encodeURIComponent('S-'))
const all = xlsxText(Buffer.from(await res0.arrayBuffer()))
if (all === null) {
  console.log('  SKIP 未找到 unzip，跳过 xlsx 内容检查')
} else {
  const exp = async (q) => xlsxText(Buffer.from(await (await raw(boss, 'GET', '/jiedan/order/export?keyword=' + encodeURIComponent('S-') + q)).arrayBuffer()))
  ok('导出新增了 已收/未收/收款情况 三列', ['报价(元)', '已收(元)', '未收(元)', '收款情况'].every((h) => all.includes(h)))
  ok('收款情况文字：已收清/部分已收/未收/未报价', ['已收清', '部分已收', '未收', '未报价'].every((h) => all.includes(h)))
  const full = await exp('&paid=1')
  ok('paid=1(已收清)：只含收清的', full.includes('S-老板的单') && !full.includes('S-下属的单') && !full.includes('S-客户的单') && !full.includes('S-一分没收'))
  const owe = await exp('&paid=0')
  ok('paid=0(还欠款)：含部分收和没收的，不含收清的和没报价的', owe.includes('S-下属的单') && owe.includes('S-客户的单') && owe.includes('S-一分没收')
    && !owe.includes('S-老板的单') && !owe.includes('S-没报价'))
  const part = await exp('&paid=part')
  ok('paid=part(部分已收)：只含收了一部分的', part.includes('S-下属的单') && part.includes('S-客户的单') && !part.includes('S-一分没收') && !part.includes('S-老板的单'))
  const byContact = xlsxText(Buffer.from(await (await raw(boss, 'GET', '/jiedan/order/export?keyword=wxid_solo9')).arrayBuffer()))
  ok('关键字能搜到联系方式', byContact.includes('S-老板的单') && !byContact.includes('S-下属的单'))
  const byReq = xlsxText(Buffer.from(await (await raw(boss, 'GET', '/jiedan/order/export?keyword=' + encodeURIComponent('蓝莓派'))).arrayBuffer()))
  ok('关键字能搜到需求内容', byReq.includes('S-老板的单') && !byReq.includes('S-下属的单'))
}

section('H 状态校验')
ok('改状态传乱码被拒', denied(await call(boss, 'PUT', '/jiedan/order/status', { id: oB, status: 'bogus' })))
ok('改状态不传被拒', denied(await call(boss, 'PUT', '/jiedan/order/status', { id: oB })))
ok('编辑里传乱码状态被拒', denied(await call(boss, 'PUT', '/jiedan/order', { id: oB, status: 'bogus' })))
ok('建单传乱码状态被拒', denied(await call(boss, 'POST', '/jiedan/order', { title: 'S-乱状态', status: 'bogus' })))
ok('被拒后状态没变', sql(`select status from jiedan_order where id=${oB}`) === 'pending')
ok('合法状态仍可改：一键直接完成', good(await call(boss, 'PUT', '/jiedan/order/status', { id: oB, status: 'done' })) && sql(`select status from jiedan_order where id=${oB}`) === 'done')
ok('完成后仍可记收款', good(await pay(boss, oB, { amount: 1, payDate: D_THIS })))
await call(boss, 'DELETE', '/jiedan/order/payment/' + (await detail(boss, oB)).payments.find((x) => x.amount === 1 || num(x.amount) === 1).id)

section('I 操作日志与彻底删除')
const addRows = await auditRows(boss, '&action=payment.add')
ok('记收款留痕：金额/日期/备注/累计', has(addRows, (x) => x.targetId === oB && /收款 .*300/.test(x.detail) && /定金/.test(x.detail) && /累计已收/.test(x.detail)), JSON.stringify(addRows[0]))
ok('下属记收款留痕为下属操作', has(addRows, (x) => x.targetId === oS && x.actorRole === 'employee' && x.actorName === '小林'))
ok('删除收款留痕', has(await auditRows(boss, '&action=payment.delete'), (x) => x.targetId === oB && /删除一笔收款/.test(x.detail)))

r = await call(boss, 'POST', '/jiedan/order', { title: 'S-待彻底删除', amount: 50 })
const oX = r.data.id
await pay(boss, oX, { amount: 50, payDate: D_THIS })
ok('彻底删除前有收款流水', sql(`select count(*) from jiedan_payment where order_id=${oX}`) === '1')
await call(boss, 'DELETE', '/jiedan/order/' + oX)
ok('进回收站时流水还在', sql(`select count(*) from jiedan_payment where order_id=${oX}`) === '1')
await call(boss, 'DELETE', `/jiedan/order/${oX}/purge`)
ok('彻底删除连带清掉收款流水', sql(`select count(*) from jiedan_payment where order_id=${oX}`) === '0')

finish()
