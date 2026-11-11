// 协作：项目「下一步」、内部记录 @同事 与已读回执、推送设置校验、升级脚本可重复执行。
import fs from 'node:fs'
import { ok, call, login, good, denied, sql, sqlFile, section, finish, DEPLOY_DIR } from './lib.mjs'

section('协作：下一步、@同事、已读回执')
const boss = await login('admin', 'admin123'); ok('管理员登录', !!boss)
const tag = Date.now().toString().slice(-8), pw = 'Collab12345'
for (const [userName, nickName] of [['ca' + tag, '协作甲'], ['cb' + tag, '协作乙']]) ok('创建员工 ' + userName, good(await call(boss, 'POST', '/jiedan/staff', { userName, nickName, password: pw })))
const staff = (await call(boss, 'GET', '/jiedan/staff/list')).data
const a = staff.find((s) => s.userName === 'ca' + tag), b = staff.find((s) => s.userName === 'cb' + tag)
const ta = await login(a.userName, pw), tb = await login(b.userName, pw)
const bossId = Number(sql("select user_id from sys_user where user_name='admin'"))
await call(boss, 'POST', '/jiedan/customer', { userName: 'cc' + tag, nickName: '协作客户', password: pw, ownerId: a.id })
const client = await login('cc' + tag, pw)

const created = await call(boss, 'POST', '/jiedan/order', { title: '协作测试-' + tag, channel: 'wechat', ownerId: a.id, customerAccount: 'cc' + tag })
const id = created.data?.id; ok('建单派给员工甲', !!id)
ok('员工甲接收', good(await call(ta, 'POST', `/jiedan/order/${id}/accept`)))
const tomorrow = (() => { const d = new Date(Date.now() + 86400000); return d.toISOString().slice(0, 10) })()

const firstBug = await call(boss, 'POST', '/jiedan/order/bug', {orderId:id, content:'核对报价'})
const issueId = firstBug.data.bugs[0].id
const issuePath = '/jiedan/order/bug/' + issueId
const actionCount = async () => (await call(ta, 'GET', '/jiedan/order/list')).data.find(o => o.id === id).actionBugCount
await call(boss, 'POST', issuePath+'/update', {content:'只是补充信息'})
ok('新增问题及追加 QA 默认不交办', Number(await actionCount()) === 0)
ok('标记后指定项目负责人看到待处理数', good(await call(boss,'PUT',issuePath+'/status',{status:'action'})) && Number(await actionCount()) === 1)
const afterRead = await call(ta,'PUT','/jiedan/order/read',{id,memberId:a.id})
ok('打开项目不消除标记', good(afterRead) && Number(await actionCount()) === 1)
const actionRows = async token => (await call(token, 'GET', '/jiedan/order/list')).data
const actionPreview = (await actionRows(ta)).find(o => o.id === id).actionBugPreviews
ok('管理列表的待处理问题提供准确的文字和编号', actionPreview.length === 1 && actionPreview[0].id === issueId && actionPreview[0].content === '核对报价')
ok('列表摘要和详情返回同一条问题，其他成员与客户不可见',
  (await call(ta,'GET','/jiedan/order/'+id)).data.actionBugPreviews[0].id === issueId &&
  !(await actionRows(tb)).some(o=>o.id===id) && !('actionBugPreviews' in (await call(client,'GET','/jiedan/portal/orders/'+id)).data))
ok('客户仍看到未解决', (await call(client,'GET','/jiedan/portal/orders/'+id)).data.bugs[0].status === 'open')
ok('解决后从待办移除', good(await call(ta,'PUT',issuePath+'/status',{status:'resolved'})) && Number(await actionCount()) === 0)

ok('同事提交后需要发起人确认', (await call(boss,'GET','/jiedan/order/'+id)).data.internalReviewIssues.some(b=>b.id===issueId))
ok('发起人确认这条问题',good(await call(boss,'PUT',issuePath+'/status',{status:'resolved',previousStatus:'ai_review'})))
const newSummary = async () => (await actionRows(ta)).find(o => o.id === id)
ok('团队问题不计入新客户问题', Number((await newSummary()).newBugCount) === 0)
const customerText='首页轮播图第二张打不开'
const customerIssue = (await call(client,'POST','/jiedan/portal/bug',{orderId:id,content:customerText})).data.bugs.find(x=>x.content===customerText)
for (const [status,newCount,openCount] of [['open',1,1],['action',0,1],['resolved',0,0]]) {
  if (status !== 'open') await call(ta,'PUT','/jiedan/order/bug/'+customerIssue.id+'/status',{status})
  const summary=await newSummary()
  const detail=(await call(ta,'GET','/jiedan/order/'+id)).data
  ok('客户问题摘要与详情一致 '+status,[summary,detail].every(o=>Number(o.newBugCount)===newCount &&
    Number(o.openBugCount)===openCount && (newCount ? o.newBugPreview?.id===customerIssue.id && o.newBugPreview.content===customerText : !o.newBugPreview)))
}

const customerStatusPath = '/jiedan/portal/bug/' + issueId + '/status'
const version = async () => Number((await call(ta,'GET',`/jiedan/order/${id}/version`)).data.version)
const previousVersion = await version()
const otherCustomer = await login('cust_b','customer123')
ok('其他客户无法重新激活该项目的问题', !!otherCustomer && denied(await call(otherCustomer,'PUT',customerStatusPath,{status:'reopen'})))
const reopened = await Promise.all(['open', 'reopen'].map(status => call(client,'PUT',customerStatusPath,{status})))
ok('客户同时从旧版和新版重新激活，负责人只新增一个待办',
  reopened.every(r => good(r) && r.data.bugs[0].status === 'open') && Number(await actionCount()) === 1)
ok('重复激活只推进一次项目版本', await version() === previousVersion + 1)
ok('重新激活的具体问题进入内部处理队列', (await call(ta,'GET',`/jiedan/order/${id}`)).data.bugs.some(bug => bug.id === issueId && bug.status === 'action'))
await call(ta,'PUT',issuePath+'/status',{status:'resolved'})
const projectStatus = sql(`select status from jiedan_order where id=${id}`)
for (const status of ['done','cancelled']) {
  sql(`update jiedan_order set status='${status}' where id=${id}`)
  ok(status+' 项目拒绝重新激活问题', denied(await call(client,'PUT',customerStatusPath,{status:'open'})) && Number(await actionCount()) === 0)
}
sql(`update jiedan_order set status='${projectStatus}' where id=${id}`)

// 手动处理状态与项目流程、问题状态、已读和编辑时间相互独立。
{
  const path = `/jiedan/order/${id}`
  const setHandled = (who, handled, version) => call(who, 'PUT', path + '/handling', { handled, version })
  const getOrder = async () => (await call(ta, 'GET', path)).data
  const getCard = async () => (await call(ta, 'GET', '/jiedan/order/list')).data.find(o => o.id === id)
  const before = await getOrder()
  const initialCard = await getCard()
  ok('新项目在详情和看板都默认为未处理且无标记时间',
    before.handled === false && before.handledTime == null && initialCard.handled === false && initialCard.handledTime == null)
  ok('其他项目成员和客户无法修改项目处理状态',
    denied(await setHandled(tb, true, before.version)) && denied(await setHandled(client, true, before.version)))
  ok('拒绝非布尔状态和缺失版本',
    denied(await setHandled(ta, 'true', before.version)) && denied(await setHandled(ta, true)))
  const manual = await setHandled(ta, false, before.version)
  const repeat = await setHandled(ta, false, before.version)
  ok('首次手动标未处理留下时间，重复提交只记录一次', good(manual) && !!manual.data.handledTime &&
    good(repeat) && manual.data.handledTime === repeat.data.handledTime && manual.data.version === before.version + 1 &&
    (await getCard()).handledTime === manual.data.handledTime)
  const marked = await Promise.all([setHandled(ta, true, manual.data.version), setHandled(boss, true, manual.data.version)])
  const after = await getOrder()
  ok('负责人和管理员同时标记已处理只产生一次更新',
    marked.every(r => good(r) && r.data.handled === true && r.data.handledTime === after.handledTime) &&
    Number(after.version) === Number(manual.data.version) + 1)
  const card = await getCard()
  ok('看板重载后与详情展示同一个标记时间，精确到秒',
    card.handled === true && card.handledTime === after.handledTime && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(card.handledTime))
  ok('手动标记保持业务阶段、问题状态和未读名单',
    after.status === before.status && JSON.stringify(after.unread) === JSON.stringify(before.unread) &&
    JSON.stringify(after.bugs) === JSON.stringify(before.bugs))
  ok('旧版本不能覆盖别人刚标记的状态', (await setHandled(boss, false, before.version)).code === 409)
  const storedTime = '2025-07-01 12:34:56'
  sql(`update jiedan_order set handled_time='${storedTime}' where id=${id}`)
  await call(ta, 'PUT', '/jiedan/order/read', { id })
  const current = await getOrder()
  const duplicate = await setHandled(ta, true, before.version)
  ok('浏览项目和重试相同标记保留原时间', current.handledTime === storedTime &&
    good(duplicate) && duplicate.data.handledTime === storedTime && duplicate.data.version === current.version)
  const reset = await setHandled(boss, false, current.version)
  ok('管理员重新标记未处理并记录本次实际切换时间',
    good(reset) && reset.data.handled === false && !!reset.data.handledTime && reset.data.handledTime !== storedTime &&
    (await getCard()).handledTime === reset.data.handledTime)
  const publicDetail = (await call(client, 'GET', '/jiedan/portal/orders/' + id)).data
  const publicCard = (await call(client, 'GET', '/jiedan/portal/orders')).data.find(o => o.id === id)
  ok('项目的内部处理标记仅在内部可见', [publicDetail, publicCard].every(o => o && !('handled' in o) && !('handledTime' in o)))
}

// 项目加急是独立的内部日期，同一日期的重试不会重复写入。
{
  const path = '/jiedan/order/' + id
  const set = (who, urgentUntil, previousUntil) => call(who, 'PUT', path + '/urgency', { urgentUntil, previousUntil })
  const read = async () => (await call(ta, 'GET', path)).data
  const card = async () => (await call(ta, 'GET', '/jiedan/order/list')).data.find(o => o.id === id)
  const before = await read()
  ok('已有项目默认没有加急日期', before.urgentUntil == null && (await card()).urgentUntil == null)
  ok('其他成员和客户不能设置加急', denied(await set(tb, tomorrow + ' 23:59', null)) && denied(await set(client, tomorrow + ' 23:59', null)))
  ok('缺失字段或无效日期不更改项目', denied(await set(ta, '2027-02-29 14:30', null)) &&
    denied(await set(ta, '2028-02-29 24:01', null)) && denied(await set(ta, false, null)) && denied(await set(ta, tomorrow)) && denied(await set(ta, undefined, null)))
  const marked = await Promise.all([set(ta, '2028-02-29 14:30', null), set(boss, '2028-02-29 14:30', null)])
  const after = await read()
  ok('管理员和负责人重复设置只更新一次，看板和详情均显示完整日期',
    marked.every(good) && after.version === before.version + 1 && after.urgentUntil === '2028-02-29 14:30' && (await card()).urgentUntil === after.urgentUntil)
  ok('加急不改变截止日期、优先级和业务状态',
    ['deadline', 'priority', 'status', 'handled'].every(k => before[k] === after[k]))
  ok('重复数据库升级保留已选日期', sqlFile(fs.readFileSync(DEPLOY_DIR + '/upgrade_project_urgency_v1.sql', 'utf8')).status === 0 && (await read()).urgentUntil === after.urgentUntil)
  ok('客户侧详情与列表不显示内部加急日期', !('urgentUntil' in (await call(client, 'GET', '/jiedan/portal/orders/' + id)).data) &&
    !('urgentUntil' in (await call(client, 'GET', '/jiedan/portal/orders')).data.find(o => o.id === id)))
  ok('加急改期后拒绝基于旧日期的取消', good(await set(boss, '2028-03-01 08:00', after.urgentUntil)) &&
    (await set(ta, null, after.urgentUntil)).code === 409 && (await read()).urgentUntil === '2028-03-01 08:00')
  ok('取消加急后重新读取详情和列表都已清除', good(await set(ta, null, '2028-03-01 08:00')) &&
    (await read()).urgentUntil == null && (await card()).urgentUntil == null)
}

// 铃铛基于当前项目和事项版本完成，避免其他人的旧页面误改刚替换的下一步。
{
  const path = '/jiedan/order/'+id
  const close = (who,kind,version,bugId) => call(who,'POST',path+'/pending-completion',{kind,version,bugId})
  const first = (await call(boss,'PUT',path+'/next',{content:'旧待办',ownerId:a.id})).data
  const changed = (await call(boss,'PUT',path+'/next',{content:'新待办',ownerId:a.id})).data
  ok('铃铛拒绝旧版本，不覆盖新待办',(await close(ta,'next',first.version)).code===409 &&
    (await call(boss,'GET',path)).data.nextStep==='新待办')
  ok('其他成员与客户不能通过铃铛标记',denied(await close(tb,'next',changed.version)) &&
    denied(await close(client,'next',changed.version)))
  ok('不能标记不存在的或已解决的问题',denied(await close(ta,'bug',changed.version,9999999)) &&
    denied(await close(ta,'bug',changed.version,issueId)))
  for (const kind of ['next','urgent','bug','manual']) {
    ok('可见项目的其他人不能在铃铛代办 '+kind,
      (await close(boss,kind,changed.version,issueId)).code===403)
  }
  const responses = await Promise.all([close(ta,'next',changed.version),close(ta,'next',changed.version)])
  const submitted = (await call(boss,'GET',path)).data
  ok('交给同事的下一步自动回到发起人', submitted.taskTurns.next.requesterId===bossId && !!submitted.taskTurns.next.submittedAt)
  ok('仅发起人确认后清除下一步', good(await call(boss,'POST',path+'/task-review',{kind:'next',decision:'confirm',version:submitted.version})))
  ok('两次同时标记只完成一次',responses.filter(good).length===1 && responses.some(x=>x.code===409) &&
    !(await call(boss,'GET',path)).data.nextStep)
  const delegated = (await call(boss,'PUT',path+'/next',{content:'确认交付范围',ownerId:bossId})).data
  ok('下一步单独指派时仅这一步的负责人可完成',
    (await close(ta,'next',delegated.version)).code===403 && good(await close(boss,'next',delegated.version)))
  const legacy = (await call(boss,'PUT',path+'/next',{content:'旧项目下一步'})).data
  sql(`update jiedan_order set next_owner_id=null where id=${id}`)
  ok('旧下一步缺少指定人时由项目负责人办理',
    (await close(boss,'next',legacy.version)).code===403 && good(await close(ta,'next',legacy.version)))
  await call(boss,'POST',path+'/task-review',{kind:'next',decision:'confirm',version:(await call(boss,'GET',path)).data.version})
}

// ---- 下一步 ----
ok('不能把下一步交给看不到项目的员工', denied(await call(boss, 'PUT', `/jiedan/order/${id}/next`, { content: '出首页设计', ownerId: b.id })))
ok('截止日期格式不对被拒', denied(await call(boss, 'PUT', `/jiedan/order/${id}/next`, { content: '出首页设计', ownerId: a.id, due: '明天' })))
ok('下一步过长被拒', denied(await call(boss, 'PUT', `/jiedan/order/${id}/next`, { content: 'x'.repeat(201) })))
ok('无权限员工不能设置下一步', denied(await call(tb, 'PUT', `/jiedan/order/${id}/next`, { content: '越权' })))
let r = await call(boss, 'PUT', `/jiedan/order/${id}/next`, { content: '出首页设计', ownerId: a.id, due: tomorrow })
ok('老板设置下一步', good(r) && r.data.nextStep === '出首页设计' && r.data.nextOwnerId === a.id && r.data.nextDue === tomorrow)
ok('下一步记入内部记录', r.data.timeline.some((t) => t.type === 'note' && t.content.includes('设置下一步：出首页设计')))
ok('员工甲被标为未读', (r.data.unread || []).includes(a.id))
const listA = (await call(ta, 'GET', '/jiedan/order/list')).data.find((o) => o.id === id)
ok('列表带下一步', listA?.nextStep === '出首页设计' && listA?.nextOwnerId === a.id && listA?.nextDue === tomorrow)
const portal = await call(client, 'GET', '/jiedan/portal/orders/' + id)
ok('客户看不到下一步', good(portal) && !('nextStep' in portal.data) && !JSON.stringify(portal.data).includes('出首页设计'))
const portalList = await call(client, 'GET', '/jiedan/portal/orders')
ok('客户列表看不到下一步', good(portalList) && !JSON.stringify(portalList.data).includes('出首页设计'))
ok('有交接时不能跳过发起人的确认直接另派',denied(await call(ta,'POST',`/jiedan/order/${id}/next/done`,{content:'老板确认首页',ownerId:bossId})))
r = await call(ta,'POST',`/jiedan/order/${id}/next/done`,{})
ok('完成记入交接记录',good(r) && r.data.timeline.some(t=>t.type==='handoff' && t.content.includes('出首页设计')))
r = await call(boss,'POST',`/jiedan/order/${id}/task-review`,{kind:'next',decision:'confirm',version:r.data.version})
ok('确认后不再安排则清空',good(r) && !r.data.nextStep && !r.data.nextOwnerId)
ok('没有下一步时不能完成', denied(await call(boss, 'POST', `/jiedan/order/${id}/next/done`, {})))
r = await call(boss, 'PUT', `/jiedan/order/${id}/next`, { content: '交付验收', ownerId: a.id })
ok('不填负责人默认交给项目负责人也可指定', good(r) && r.data.nextOwnerId === a.id)
r = await call(boss, 'PUT', `/jiedan/order/${id}/next`, { content: '' })
ok('内容为空即清除下一步', good(r) && !r.data.nextStep && r.data.timeline.some((t) => t.content.includes('清除了下一步')))
await call(boss, 'PUT', `/jiedan/order/${id}/next`, { content: '给客户演示' })
r = await call(boss, 'GET', '/jiedan/order/' + id)
ok('默认交给负责人', r.data.nextOwnerId === a.id)
// 换负责人：交给旧负责人的下一步跟着走
const full = r.data
ok('改派给员工乙', good(await call(boss, 'PUT', '/jiedan/order', { ...full, ownerId: b.id })))
r = await call(boss, 'GET', '/jiedan/order/' + id)
ok('改派后下一步跟着交给新负责人', r.data.nextOwnerId === b.id && r.data.nextStep === '给客户演示')
ok('员工乙接收', good(await call(tb, 'POST', `/jiedan/order/${id}/accept`)))

// ---- @同事 与已读回执 ----
ok('不能 @ 看不到项目的人', denied(await call(boss, 'POST', '/jiedan/order/progress', { orderId: id, type: 'note', content: '@甲 看下', mentions: [a.id] })))
ok('客户沟通不能 @', denied(await call(boss, 'POST', '/jiedan/order/progress', { orderId: id, type: 'reply', content: '你好', mentions: [b.id] })))
r = await call(boss, 'POST', '/jiedan/order/progress', { orderId: id, type: 'note', content: '@协作乙 客户说首页颜色要深一点', mentions: [b.id, b.id, bossId] })
const note = r.data?.timeline?.find((t) => t.content === '@协作乙 客户说首页颜色要深一点')
ok('@ 记录成功（重复和自己被忽略）', good(r) && note?.mentions?.length === 1 && note.mentions[0].memberId === b.id && note.mentions[0].read === false)
let rowB = (await call(tb, 'GET', '/jiedan/order/list')).data.find((o) => o.id === id)
ok('被 @ 的人列表里有“@我”', rowB?.myMention?.content?.includes('首页颜色') && !!rowB.myMention.from)
const rowBoss = (await call(boss, 'GET', '/jiedan/order/list')).data.find((o) => o.id === id)
ok('没被 @ 的人没有“@我”', !rowBoss?.myMention)
const v0 = (await call(boss, 'GET', `/jiedan/order/${id}/version`)).data.version
ok('被 @ 的人打开项目', good(await call(tb, 'PUT', '/jiedan/order/read', { id })))
r = await call(boss, 'GET', '/jiedan/order/' + id)
ok('发 @ 的人看到“已读”', r.data.timeline.find((t) => t.id === note.id)?.mentions?.[0]?.read === true)
ok('已读后版本号变化，其他设备会刷新', (await call(boss, 'GET', `/jiedan/order/${id}/version`)).data.version > v0)
rowB = (await call(tb, 'GET', '/jiedan/order/list')).data.find((o) => o.id === id)
ok('看过后“@我”消失', !rowB?.myMention)
ok('客户看不到内部 @', !JSON.stringify((await call(client, 'GET', '/jiedan/portal/orders/' + id)).data).includes('首页颜色'))
r = await call(tb, 'POST', '/jiedan/order/progress', { orderId: id, type: 'note', content: '收到，已改', mentions: [bossId] })
ok('员工可以 @ 老板', good(r) && r.data.timeline.some((t) => t.content === '收到，已改' && t.mentions?.[0]?.memberId === bossId))
const preview = await call(boss, 'GET', '/jiedan/push/digest')
ok('每日汇总提醒老板有没看的 @', good(preview) && (preview.data.find((d) => d.memberId === bossId)?.body || '').includes('@你 的内部记录还没看'))

// ---- 推送设置 ----
ok('系统网址格式不对被拒', denied(await call(boss, 'POST', '/jiedan/push/config', { 'push.siteUrl': 'jiedan.example.com' })))
ok('停滞天数超范围被拒', denied(await call(boss, 'POST', '/jiedan/push/config', { 'push.stallDays': '0' })))
ok('员工不能改系统网址', denied(await call(ta, 'POST', '/jiedan/push/config', { 'push.siteUrl': 'https://x.example.com' })))
ok('老板设置系统网址与停滞天数', good(await call(boss, 'POST', '/jiedan/push/config', { 'push.siteUrl': 'https://jiedan.example.com/', 'push.stallDays': '5' })))
const cfg = (await call(boss, 'GET', '/jiedan/push/config')).data
ok('网址去掉末尾斜杠后保存', cfg['push.siteUrl'] === 'https://jiedan.example.com' && cfg['push.stallDays'] === '5')
await call(boss, 'POST', '/jiedan/push/config', { 'push.siteUrl': '', 'push.stallDays': '3' })

// ---- 停滞：超过天数没动静的项目进汇总 ----
sql(`update jiedan_order set update_time = date_sub(now(), interval 10 day) where id = ${id}`)
const stalled = await call(boss, 'GET', '/jiedan/push/digest')
ok('停滞项目进入负责人的汇总', (stalled.data.find((d) => d.memberId === b.id)?.body || '').includes('超过 3 天没有动静'))

// ---- 彻底删除连带清掉 @ ----
ok('删除项目', good(await call(boss, 'DELETE', '/jiedan/order/' + id)))
ok('彻底删除', good(await call(boss, 'DELETE', `/jiedan/order/${id}/purge`)))
ok('@ 记录随项目清掉', Number(sql(`select count(*) from jiedan_mention where order_id = ${id}`)) === 0)

// ---- 升级脚本可重复执行 ----
for (let i = 0; i < 2; i++) ok('协作升级脚本可重复执行', sqlFile(fs.readFileSync(DEPLOY_DIR + '/upgrade_collab_v1.sql', 'utf8')).status === 0)
finish()
