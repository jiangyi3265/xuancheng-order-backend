// 流转与审批端到端测试：派单后接收/拒绝、员工交回、老板退回返工、员工敏感操作的闸门、
// 申请（改报价/取消/删除/收款）的提交-批准-驳回-撤回。
// 用自己建的账号（fl1/fl2/fc1），可以在 solo.test.mjs 之后跑，也可以单独对新库跑。
import { ok, call, login, good, denied, sql, section, finish } from './lib.mjs'

const has = (rows, fn) => Array.isArray(rows) && rows.some(fn)
const num = (v) => Number(v)
const pad = (n) => String(n).padStart(2, '0')
const now = new Date()
const TODAY = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`

const detail = async (t, id) => (await call(t, 'GET', '/jiedan/order/' + id)).data
const listOf = async (t) => (await call(t, 'GET', '/jiedan/order/list')).data
const rowOf = async (t, id) => (await listOf(t)).find((o) => o.id === id)
const auditRows = async (t, q = '') => {
  const r = await call(t, 'GET', '/jiedan/audit/list?pageSize=100' + q)
  return good(r) ? r.data.rows : []
}
const approvals = async (t, status) => (await call(t, 'GET', '/jiedan/approval/list' + (status ? '?status=' + status : ''))).data || []
const request = (t, body) => call(t, 'POST', '/jiedan/approval', body)
const setStatus = (t, id, status) => call(t, 'PUT', '/jiedan/order/status', { id, status })
const post = (t, path, body = {}) => call(t, 'POST', path, body)

section('A 准备账号')
const boss = await login('admin', 'admin123')
ok('admin 登录', !!boss)
ok('创建员工 fl1(小王)', good(await post(boss, '/jiedan/staff', { userName: 'fl1', nickName: '小王', password: 'fl1pass' })))
ok('创建员工 fl2(小李)', good(await post(boss, '/jiedan/staff', { userName: 'fl2', nickName: '小李', password: 'fl2pass' })))
const staff = (await call(boss, 'GET', '/jiedan/staff/list')).data
const fl1 = staff.find((x) => x.userName === 'fl1').id
const fl2 = staff.find((x) => x.userName === 'fl2').id
const bossId = staff.find((x) => x.role === 'boss').id
const t1 = await login('fl1', 'fl1pass')
const t2 = await login('fl2', 'fl2pass')
ok('两个员工都能登录', !!t1 && !!t2)
ok('创建客户 fc1', good(await post(boss, '/jiedan/customer', { userName: 'fc1', nickName: '流转客', password: 'fc1pass' })))
const c1 = await login('fc1', 'fc1pass')
ok('客户登录', !!c1)

// 建一个派给 fl1 并已接收、进行中的项目
async function workingOrder(title, amount = 1000, owner = fl1, token = t1) {
  const r = await post(boss, '/jiedan/order', { title, customer: '甲', amount, ownerId: owner })
  const id = r.data.id
  await post(token, `/jiedan/order/${id}/accept`)
  await setStatus(token, id, 'doing')
  return id
}

section('B 派单后要接收')
let r = await post(boss, '/jiedan/order', { title: 'F-派单测试', customer: '甲', amount: 1000, ownerId: fl1 })
const oA = r.data.id
ok('老板建单给员工：状态为“等对方接收”', good(r) && r.data.assignState === 'pending', JSON.stringify(r.data))
ok('员工列表里也是 pending', (await rowOf(t1, oA))?.assignState === 'pending')
ok('接收前员工不能改状态', denied(await setStatus(t1, oA, 'doing')))
ok('接收前员工不能提交申请', denied(await request(t1, { orderId: oA, type: 'delete', reason: '试试' })))
ok('老板不能“接收”', denied(await post(boss, `/jiedan/order/${oA}/accept`)))
ok('别的员工不能接收', denied(await post(t2, `/jiedan/order/${oA}/accept`)))
r = await post(t1, `/jiedan/order/${oA}/accept`)
ok('员工接收成功，状态恢复为空', good(r) && r.data.assignState === '', JSON.stringify(r))
ok('接收后员工能开始做', good(await setStatus(t1, oA, 'doing')))
ok('重复接收被拒', denied(await post(t1, `/jiedan/order/${oA}/accept`)))
ok('时间线记了“接收了这个项目”', (await detail(boss, oA)).timeline.some((t) => /小王 接收了这个项目/.test(t.content)))
ok('老板因此有未读', (await rowOf(boss, oA)).unread.includes(bossId))
ok('接收留痕', has(await auditRows(boss, '&action=order.accept'), (x) => x.targetId === oA && x.actorRole === 'employee'))

r = await post(boss, '/jiedan/order', { title: 'F-老板自己的', customer: '乙', amount: 10 })
ok('老板建给自己：无需接收', good(r) && r.data.assignState === '')
r = await post(boss, '/jiedan/order', { title: 'F-转派又转回', customer: '乙', amount: 10, ownerId: fl2 })
const oBack = r.data.id
ok('老板改派到员工：pending', (await call(boss, 'PUT', '/jiedan/order', { id: oBack, ownerId: fl1 })).data.assignState === 'pending')
ok('老板再转回自己：无需接收', (await call(boss, 'PUT', '/jiedan/order', { id: oBack, ownerId: bossId })).data.assignState === '')
ok('客户不能接收', denied(await post(c1, `/jiedan/order/${oA}/accept`)))
ok('无令牌不能接收', denied(await post(null, `/jiedan/order/${oA}/accept`)))

section('C 员工拒绝刚派来的项目')
r = await post(boss, '/jiedan/order', { title: 'F-会被拒绝', customer: '丙', amount: 500, ownerId: fl1 })
const oB = r.data.id
ok('拒绝不写原因被拒', denied(await post(t1, `/jiedan/order/${oB}/reject`, { reason: '  ' })))
ok('老板不能“拒绝”', denied(await post(boss, `/jiedan/order/${oB}/reject`, { reason: 'x' })))
ok('已接收过的项目不能再“拒绝”（要用交回）', denied(await post(t1, `/jiedan/order/${oA}/reject`, { reason: 'x' })))
ok('别的员工不能拒绝', denied(await post(t2, `/jiedan/order/${oB}/reject`, { reason: 'x' })))
r = await post(t1, `/jiedan/order/${oB}/reject`, { reason: '这周排满了' })
ok('拒绝成功', good(r), JSON.stringify(r))
ok('项目回到老板名下，状态待确认，无需接收', sql(`select concat(owner_id, '|', status, '|', assign_state) from jiedan_order where id=${oB}`) === `${bossId}|pending|`)
ok('拒绝后员工看不到这个项目了', denied(await call(t1, 'GET', '/jiedan/order/' + oB)))
ok('老板能看到，时间线里有原因', (await detail(boss, oB)).timeline.some((t) => /小王 拒绝了老板派来的项目：这周排满了/.test(t.content)))
ok('老板有未读', (await rowOf(boss, oB)).unread.includes(bossId))
ok('拒绝留痕带原因', has(await auditRows(boss, '&action=order.reject'), (x) => x.targetId === oB && /这周排满了/.test(x.detail)))
ok('老板可以重新派给别人', good(await call(boss, 'PUT', '/jiedan/order', { id: oB, ownerId: fl2 })))

section('D 员工把项目交回老板')
const oC = await workingOrder('F-会被交回')
ok('交回不写原因被拒', denied(await post(t1, `/jiedan/order/${oC}/handback`, { reason: '' })))
ok('别的员工不能交回', denied(await post(t2, `/jiedan/order/${oC}/handback`, { reason: 'x' })))
r = await post(boss, '/jiedan/order', { title: 'F-老板名下', customer: '丁', amount: 1 })
ok('老板名下的项目不需要交回', denied(await post(boss, `/jiedan/order/${r.data.id}/handback`, { reason: 'x' })))
r = await post(t1, `/jiedan/order/${oC}/handback`, { reason: '这个我不会做' })
ok('交回成功', good(r), JSON.stringify(r))
ok('回到老板名下，变成待确认', sql(`select concat(owner_id, '|', status, '|', assign_state) from jiedan_order where id=${oC}`) === `${bossId}|pending|`)
ok('交回后员工看不到了', denied(await call(t1, 'GET', '/jiedan/order/' + oC)))
ok('时间线里有原因', (await detail(boss, oC)).timeline.some((t) => /小王 把项目交回给老板：这个我不会做/.test(t.content)))
ok('老板有未读', (await rowOf(boss, oC)).unread.includes(bossId))
ok('交回留痕', has(await auditRows(boss, '&action=order.handback'), (x) => x.targetId === oC && /这个我不会做/.test(x.detail)))
const oDone = await workingOrder('F-已完成不能交回')
await setStatus(boss, oDone, 'done')
ok('已完成的项目不能交回', denied(await post(t1, `/jiedan/order/${oDone}/handback`, { reason: 'x' })))

section('E 老板退回返工')
const oD = await workingOrder('F-验收不通过')
ok('进行中的项目不能退回返工', denied(await post(boss, `/jiedan/order/${oD}/return`, { reason: 'x' })))
ok('员工提交验收', good(await setStatus(t1, oD, 'review')))
ok('员工不能退回返工（只有老板能）', denied(await post(t1, `/jiedan/order/${oD}/return`, { reason: 'x' })))
ok('客户不能退回返工', denied(await post(c1, `/jiedan/order/${oD}/return`, { reason: 'x' })))
ok('退回不写原因被拒', denied(await post(boss, `/jiedan/order/${oD}/return`, { reason: '' })))
const revBefore = num((await detail(boss, oD)).revisions)
r = await post(boss, `/jiedan/order/${oD}/return`, { reason: '配色不对，重做' })
ok('退回成功，回到进行中', good(r) && r.data.status === 'doing', JSON.stringify(r))
ok('改稿次数 +1', num(r.data.revisions) === revBefore + 1)
ok('时间线有原因', r.data.timeline.some((t) => /尚琦凯 退回返工：配色不对，重做/.test(t.content)))
ok('员工有未读', (await rowOf(t1, oD)).unread.includes(fl1))
ok('退回留痕', has(await auditRows(boss, '&action=order.return'), (x) => x.targetId === oD && /配色不对/.test(x.detail)))
await setStatus(t1, oD, 'review')
await setStatus(boss, oD, 'done')
ok('已完成的项目也能退回重开', good(await post(boss, `/jiedan/order/${oD}/return`, { reason: '客户又改主意了' })))
r = await post(boss, '/jiedan/order', { title: 'F-待确认', customer: '戊', amount: 1 })
ok('待确认的项目不能退回返工', denied(await post(boss, `/jiedan/order/${r.data.id}/return`, { reason: 'x' })))

section('F 员工敏感操作的闸门（必须走申请）')
const oE = await workingOrder('F-闸门', 2000)
ok('员工不能直接改报价', denied(await call(t1, 'PUT', '/jiedan/order', { id: oE, amount: 1500 })))
r = await call(t1, 'PUT', '/jiedan/order', { id: oE, amount: 2000, title: 'F-闸门(改名)', status: 'doing' })
ok('员工编辑时报价/状态没变是放行的（编辑窗口会把原值带回来）', good(r) && r.data.title === 'F-闸门(改名)', JSON.stringify(r))
r = await setStatus(t1, oE, 'cancelled')
ok('员工不能直接取消（状态接口）', denied(r) && /申请/.test(r.msg || ''), JSON.stringify(r))
ok('员工不能直接取消（编辑接口）', denied(await call(t1, 'PUT', '/jiedan/order', { id: oE, status: 'cancelled' })))
r = await setStatus(t1, oE, 'done')
ok('员工不能直接标已完成，提示走提交验收', denied(r) && /提交验收/.test(r.msg || ''), JSON.stringify(r))
ok('员工不能直接标已完成（编辑接口）', denied(await call(t1, 'PUT', '/jiedan/order', { id: oE, status: 'done' })))
ok('员工能提交验收', good(await setStatus(t1, oE, 'review')))
ok('员工能改回进行中', good(await setStatus(t1, oE, 'doing')))
ok('员工不能直接删除', denied(await call(t1, 'DELETE', '/jiedan/order/' + oE)))
ok('员工不能直接记收款', denied(await post(t1, `/jiedan/order/${oE}/payment`, { amount: 100, payDate: TODAY })))
ok('老板记一笔收款', good(await post(boss, `/jiedan/order/${oE}/payment`, { amount: 100, payDate: TODAY, note: '定金' })))
const payId = (await detail(boss, oE)).payments[0].id
ok('员工不能删除收款记录', denied(await call(t1, 'DELETE', '/jiedan/order/payment/' + payId)))
ok('被拒后项目没变', sql(`select concat(status, '|', amount, '|', del_flag, '|', received_amount) from jiedan_order where id=${oE}`) === 'doing|2000.00|0|100.00')
ok('员工仍然能写进度、记 Bug（日常操作不受影响）', good(await post(t1, '/jiedan/order/progress', { orderId: oE, content: '做了一半', type: 'note' })) && good(await post(t1, '/jiedan/order/bug', { orderId: oE, content: '一个 bug' })))

r = await post(boss, '/jiedan/order', { title: 'F-老板不受闸门影响', customer: '己', amount: 100 })
const oBoss = r.data.id
ok('老板能直接改报价', good(await call(boss, 'PUT', '/jiedan/order', { id: oBoss, amount: 200 })))
ok('老板能直接记收款', good(await post(boss, `/jiedan/order/${oBoss}/payment`, { amount: 50, payDate: TODAY })))
ok('老板能直接取消', good(await setStatus(boss, oBoss, 'cancelled')))
ok('老板能直接删除', good(await call(boss, 'DELETE', '/jiedan/order/' + oBoss)))

section('G 申请：改报价')
const oF = await workingOrder('F-申请改价', 1000)
ok('原因必填', denied(await request(t1, { orderId: oF, type: 'amount', reason: '', payload: { amount: 800 } })))
ok('新报价格式不对被拒', denied(await request(t1, { orderId: oF, type: 'amount', reason: 'x', payload: { amount: 'abc' } })))
ok('新报价为负数被拒', denied(await request(t1, { orderId: oF, type: 'amount', reason: 'x', payload: { amount: -1 } })))
ok('新报价和现在一样被拒', denied(await request(t1, { orderId: oF, type: 'amount', reason: 'x', payload: { amount: 1000 } })))
ok('不认识的申请类型被拒', denied(await request(t1, { orderId: oF, type: 'hack', reason: 'x', payload: {} })))
r = await request(t1, { orderId: oF, type: 'amount', reason: '客户砍价', payload: { amount: 800 } })
ok('提交申请成功', good(r) && r.data.status === 'pending', JSON.stringify(r))
const idAmt = r.data.id
ok('提交后订单报价没变', num((await detail(boss, oF)).amount) === 1000)
ok('同一项目同一类申请不能重复提交', denied(await request(t1, { orderId: oF, type: 'amount', reason: '再来一次', payload: { amount: 700 } })))
const pend = await approvals(boss, 'pending')
const mine = pend.find((a) => a.id === idAmt)
ok('老板的待批列表里有它，写明改了什么', !!mine && /改报价 ¥1000 → ¥800/.test(mine.summary) && mine.requesterName === '小王' && mine.orderTitle === 'F-申请改价', JSON.stringify(mine))
ok('员工能看到自己的申请，别的员工看不到', has(await approvals(t1), (a) => a.id === idAmt) && !has(await approvals(t2), (a) => a.id === idAmt))
ok('项目详情不夹带申请明细（申请统一从申请列表取）', !('approvals' in (await detail(boss, oF))))
ok('项目列表里带着待批数量', num((await rowOf(boss, oF)).pendingApprovals) === 1)
ok('老板有未读（提醒去批）', (await rowOf(boss, oF)).unread.includes(bossId))
ok('申请留痕', has(await auditRows(boss, '&action=approval.request'), (x) => x.targetId === oF && /改报价/.test(x.detail) && /客户砍价/.test(x.detail)))
ok('别的员工不能撤回', denied(await post(t2, `/jiedan/approval/${idAmt}/withdraw`)))
ok('别的员工不能批准', denied(await post(t2, `/jiedan/approval/${idAmt}/approve`)))
ok('提交人自己不能批准', denied(await post(t1, `/jiedan/approval/${idAmt}/approve`)))
ok('客户不能批准、不能看列表、不能提交', denied(await post(c1, `/jiedan/approval/${idAmt}/approve`)) && denied(await call(c1, 'GET', '/jiedan/approval/list')) && denied(await request(c1, { orderId: oF, type: 'delete', reason: 'x' })))
ok('无令牌不能看列表', denied(await call(null, 'GET', '/jiedan/approval/list')))
ok('老板不需要提交申请', denied(await request(boss, { orderId: oF, type: 'delete', reason: 'x' })))
ok('员工不能给别人的项目提交申请', denied(await request(t2, { orderId: oF, type: 'delete', reason: 'x' })))
ok('驳回不写原因被拒', denied(await post(boss, `/jiedan/approval/${idAmt}/reject`, { note: ' ' })))
ok('批准成功', good(await post(boss, `/jiedan/approval/${idAmt}/approve`, { note: '行' })))
ok('批准后报价才生效', num((await detail(boss, oF)).amount) === 800)
const done = (await approvals(boss)).find((a) => a.id === idAmt)
ok('申请变成已批准，记了处理人', done.status === 'approved' && done.handlerName === '尚琦凯' && done.handleNote === '行', JSON.stringify(done))
ok('重复批准被拒', denied(await post(boss, `/jiedan/approval/${idAmt}/approve`)))
ok('批准后撤回也被拒', denied(await post(t1, `/jiedan/approval/${idAmt}/withdraw`)))
ok('员工有未读，时间线有批准记录', (await rowOf(t1, oF)).unread.includes(fl1) && (await detail(boss, oF)).timeline.some((t) => /批准了 小王 的申请：改报价/.test(t.content)))
ok('批准留痕', has(await auditRows(boss, '&action=approval.approve'), (x) => x.targetId === oF))
ok('批准后待批数量归零', num((await rowOf(boss, oF)).pendingApprovals) === 0)

r = await request(t1, { orderId: oF, type: 'amount', reason: '再降一点', payload: { amount: 700 } })
const idRej = r.data.id
ok('老板驳回（写原因）', good(await post(boss, `/jiedan/approval/${idRej}/reject`, { note: '不能再降了' })))
ok('驳回后报价没变，申请记着原因', num((await detail(boss, oF)).amount) === 800 && (await approvals(t1)).find((a) => a.id === idRej).status === 'rejected' && (await approvals(t1)).find((a) => a.id === idRej).handleNote === '不能再降了')
ok('驳回留痕', has(await auditRows(boss, '&action=approval.reject'), (x) => x.targetId === oF && /不能再降了/.test(x.detail)))
r = await request(t1, { orderId: oF, type: 'amount', reason: '算错了', payload: { amount: 600 } })
const idWd = r.data.id
ok('员工撤回自己的申请', good(await post(t1, `/jiedan/approval/${idWd}/withdraw`)))
ok('撤回后老板批不了', denied(await post(boss, `/jiedan/approval/${idWd}/approve`)))
ok('撤回后可以重新申请', good(await request(t1, { orderId: oF, type: 'amount', reason: '重新来', payload: { amount: 650 } })))

section('H 申请：取消 / 删除')
const oG = await workingOrder('F-申请取消', 300)
r = await request(t1, { orderId: oG, type: 'cancel', reason: '客户不要了' })
ok('提交取消申请', good(r))
const idCancel = r.data.id
ok('取消申请重复提交被拒', denied(await request(t1, { orderId: oG, type: 'cancel', reason: '又一次' })))
ok('提交后项目还是进行中', sql(`select status from jiedan_order where id=${oG}`) === 'doing')
ok('批准取消', good(await post(boss, `/jiedan/approval/${idCancel}/approve`)))
ok('项目被取消', sql(`select status from jiedan_order where id=${oG}`) === 'cancelled')
ok('已取消的项目不能再申请取消', denied(await request(t1, { orderId: oG, type: 'cancel', reason: 'x' })))

const oH = await workingOrder('F-申请删除', 300)
r = await request(t1, { orderId: oH, type: 'delete', reason: '建重复了' })
const idDel = r.data.id
ok('提交删除申请', good(r))
ok('批准前项目还在', sql(`select del_flag from jiedan_order where id=${oH}`) === '0')
ok('批准删除', good(await post(boss, `/jiedan/approval/${idDel}/approve`)))
ok('项目进了回收站（不是物理删除）', sql(`select del_flag from jiedan_order where id=${oH}`) === '1')
ok('员工看不到已删除的项目', denied(await call(t1, 'GET', '/jiedan/order/' + oH)))
ok('申请记录仍然保留', sql(`select status from jiedan_approval where id=${idDel}`) === 'approved')
ok('老板彻底删除时一并清掉申请记录', good(await call(boss, 'DELETE', `/jiedan/order/${oH}/purge`)) && sql(`select count(*) from jiedan_approval where order_id=${oH}`) === '0')

section('I 申请：收款')
const oI = await workingOrder('F-申请收款', 1000)
ok('收款金额为 0 在提交时就被拒', denied(await request(t1, { orderId: oI, type: 'payment', reason: 'x', payload: { amount: 0, payDate: TODAY } })))
ok('收款日期是将来的在提交时就被拒', denied(await request(t1, { orderId: oI, type: 'payment', reason: 'x', payload: { amount: 10, payDate: '2099-01-01' } })))
r = await request(t1, { orderId: oI, type: 'payment', reason: '客户微信转账给我了', payload: { amount: 300, payDate: TODAY, note: '定金' } })
const idPay1 = r.data.id
ok('提交收款申请', good(r), JSON.stringify(r))
r = await request(t1, { orderId: oI, type: 'payment', reason: '又转了一笔', payload: { amount: 200, payDate: TODAY, note: '追加' } })
const idPay2 = r.data.id
ok('收款可以分几笔分别申请', good(r))
ok('批准前不计入已收', sql(`select received_amount from jiedan_order where id=${oI}`) === '0.00')
const incomeBefore = num((await call(boss, 'GET', '/jiedan/order/income')).data.total)
ok('批准第一笔', good(await post(boss, `/jiedan/approval/${idPay1}/approve`)))
ok('批准后才入账：已收 300', sql(`select received_amount from jiedan_order where id=${oI}`) === '300.00')
ok('收款记在申请人名下（小王）', sql(`select created_by from jiedan_payment where order_id=${oI}`) === '小王')
ok('实收统计增加 300', num((await call(boss, 'GET', '/jiedan/order/income')).data.total) === incomeBefore + 300)
ok('驳回第二笔', good(await post(boss, `/jiedan/approval/${idPay2}/reject`, { note: '还没到账' })))
ok('被驳回的不入账', sql(`select received_amount from jiedan_order where id=${oI}`) === '300.00')
ok('员工在详情里看到已入账的收款', (await detail(t1, oI)).payments.length === 1)

section('J 其他边界')
r = await post(boss, '/jiedan/order', { title: 'F-还没接收', customer: '庚', amount: 10, ownerId: fl1 })
ok('没接收的项目提交申请被拒', denied(await request(t1, { orderId: r.data.id, type: 'delete', reason: 'x' })))
ok('员工的待批列表只含 pending', (await approvals(t1, 'pending')).every((a) => a.status === 'pending'))
ok('老板的全部列表包含各种状态', new Set((await approvals(boss)).map((a) => a.status)).size >= 3)
const flowLog = await auditRows(boss, '&keyword=' + encodeURIComponent('F-'))
ok('操作日志里能查到流转与申请的记录', ['order.accept', 'order.reject', 'order.handback', 'order.return', 'approval.request', 'approval.approve', 'approval.reject', 'approval.withdraw'].every((a) => has(flowLog, (x) => x.action === a)),
  JSON.stringify([...new Set(flowLog.map((x) => x.action))]))

finish()
