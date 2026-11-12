// 交接：提交时带一句话；项目验收 / 退回返工也记成一条交接，退回原因跟着走。
import fs from 'node:fs'
import { ok, call, login, good, denied, sql, sqlFile, section, finish, DEPLOY_DIR } from './lib.mjs'

section('交接：提交说明、项目验收与退回返工')
const boss = await login('admin', 'admin123'); ok('管理员登录', !!boss)
const tag = Date.now().toString().slice(-8), pw = 'Handoff12345'
ok('创建员工', good(await call(boss, 'POST', '/jiedan/staff', { userName: 'hn' + tag, nickName: '交接员工', password: pw })))
const staff = (await call(boss, 'GET', '/jiedan/staff/list')).data.find((s) => s.userName === 'hn' + tag)
const emp = await login(staff.userName, pw)
const bossId = Number(sql("select user_id from sys_user where user_name='admin'"))
const id = (await call(boss, 'POST', '/jiedan/order', { title: '交接说明-' + tag, channel: 'wechat', ownerId: staff.id })).data.id
const path = '/jiedan/order/' + id
ok('员工接收并开工', good(await call(emp, 'POST', path + '/accept')) && good(await call(emp, 'PUT', '/jiedan/order/status', { id, status: 'doing' })))
const detail = async (who = boss) => (await call(who, 'GET', path)).data
const row = async (who) => (await call(who, 'GET', '/jiedan/order/list')).data.find((o) => o.id === id)

// ---- 下一步：提交时带一句话 ----
ok('老板交代下一步', good(await call(boss, 'PUT', path + '/next', { content: '把首页初稿发给客户', ownerId: staff.id, due: '' })))
let d = await detail()
ok('超过 300 字的说明被拒绝，事项不变', denied(await call(emp, 'POST', path + '/pending-completion', { kind: 'next', version: d.version, note: '字'.repeat(301) })) &&
  !(await detail()).taskTurns.next.submittedAt)
d = await detail()
ok('员工提交并写了一句话', good(await call(emp, 'POST', path + '/pending-completion', { kind: 'next', version: d.version, note: '  已发给客户，等他周五回复  ' })))
d = await detail()
ok('说明记在这条交接上，前后空格去掉', d.taskTurns.next.submitNote === '已发给客户，等他周五回复' && !!d.taskTurns.next.submittedAt)
ok('列表里也带着说明', (await row(boss)).taskTurns.next.submitNote === '已发给客户，等他周五回复')
ok('内部记录里写了这句话', d.timeline.some((t) => t.type === 'handoff' && String(t.content).includes('已发给客户，等他周五回复')))
ok('确认人不能替提交者撤回', denied(await call(boss, 'PUT', path + '/handoff-withdraw', {kind: 'next', version: d.version})))
ok('员工撤回后原任务仍在，提交说明清空', good(await call(emp, 'PUT', path + '/handoff-withdraw', {kind: 'next', version: d.version})) &&
  !(await detail()).taskTurns.next.submittedAt && (await detail()).taskTurns.next.submitNote == null)
ok('旧页面不能确认已经撤回的提交', denied(await call(boss, 'POST', path + '/task-review', {kind: 'next', decision: 'confirm', version: d.version})))
d = await detail()
ok('撤回后可以再提交', good(await call(emp, 'POST', path + '/pending-completion', {kind: 'next', version: d.version})))
d = await detail()
ok('老板退回并写原因', good(await call(boss, 'POST', path + '/task-review', { kind: 'next', decision: 'return', note: '换一张大图', version: d.version })))
d = await detail()
ok('退回后上一次的说明清掉，退回原因在', d.taskTurns.next.submitNote == null && d.taskTurns.next.returnNote === '换一张大图' && !d.taskTurns.next.submittedAt)
ok('不写说明也能提交', good(await call(emp, 'POST', path + '/pending-completion', { kind: 'next', version: d.version })))
d = await detail()
ok('再提交时没有说明，退回记录还在', d.taskTurns.next.submitNote == null && !!d.taskTurns.next.submittedAt && !!d.taskTurns.next.returnedAt)
ok('重新提交显示第 2 次，撤回不算做退回修改', d.taskTurns.next.submissionCount === 2)
ok('再退一次', good(await call(boss, 'POST', path + '/task-review', {kind:'next', decision:'return', note:'手机版也要改', version:d.version})))
d = await detail()
ok('第三次提交', good(await call(emp, 'POST', path + '/pending-completion', {kind:'next', note:'手机也测过了', version:d.version})))
d = await detail()
ok('多次返工计数准确', d.taskTurns.next.submissionCount === 3)
ok('老板确认', good(await call(boss, 'POST', path + '/task-review', { kind: 'next', decision: 'confirm', version: d.version })))

const receipt = (await row(emp)).recentApprovalEvents.find((e) => e.kind === 'next')
ok('通过事件有独立 id、真实验收人、提交人和项目，只有提交人可见', receipt?.id > 0 && receipt.actorId === bossId && receipt.submitterId === staff.id &&
  receipt.orderId === id && receipt.text.includes('把首页初稿发给客户') && !(await row(boss)).recentApprovalEvents.some((e) => e.id === receipt.id))
d = await detail()
ok('通过后不能撤回', denied(await call(emp,'PUT',path+'/handoff-withdraw',{kind:'next',version:d.version})))

// ---- 交办的问题：提交时带一句话 ----
const bugVo = await call(boss, 'POST', '/jiedan/order/bug', { orderId: id, content: '导航栏点不开', needsAction: true })
const bugId = bugVo.data.bugs.map((b) => b.id).sort((a, b) => b - a)[0]
d = await detail()
ok('员工提交问题并写说明', good(await call(emp, 'POST', path + '/pending-completion', { kind: 'bug', bugId, version: d.version, note: '是层级问题，已修' })))
d = await detail()
ok('问题这条交接带着说明', d.taskTurns['bug-' + bugId]?.submitNote === '是层级问题，已修')
ok('老板通过这条问题', good(await call(boss, 'PUT', '/jiedan/order/bug/' + bugId + '/status', { status: 'resolved', previousStatus: 'ai_review' })))

// 加急交接复用任务说明列。撤回恢复原截止时间，再次提交后可以确认。
ok('新增加急', good(await call(boss,'PUT',path+'/urgency',{previousUntil:null,urgentUntil:'2030-12-12 16:30'})))
d = await detail()
ok('提交加急说明', good(await call(emp,'POST',path+'/pending-completion',{kind:'urgent',version:d.version,note:'加急交付完成'})))
d = await detail()
ok('加急说明对列表和详情一致', d.urgentHandoff.submitNote === '加急交付完成' && (await row(boss)).urgentHandoff.submitNote === '加急交付完成')
ok('撤回加急并恢复截止', good(await call(emp,'PUT',path+'/handoff-withdraw',{kind:'urgent',version:d.version})) &&
  (await detail()).urgentUntil === '2030-12-12 16:30' && (await detail()).urgentHandoff.submitNote == null)
d = await detail()
ok('再次提交加急', good(await call(emp,'POST',path+'/pending-completion',{kind:'urgent',version:d.version})))
d = await detail()
ok('加急确认回执', good(await call(boss,'POST',path+'/urgent-review',{decision:'confirm',version:d.version})) &&
  (await row(emp)).recentApprovalEvents.some((e) => e.kind === 'urgent'))

// 项目手动标记与其他事项独立确认，撤回不会同时完成或删除其他任务。
d = await detail()
ok('发起手动未处理', good(await call(boss,'PUT',path+'/handling',{handled:false,version:d.version})))
d = await detail()
ok('手动标记从原详情处理接口也可以带说明', good(await call(emp,'PUT',path+'/handling',
  {handled:true,version:d.version,note:'已经同步客户'})) && (await detail()).taskTurns.manual.submitNote === '已经同步客户')
d = await detail()
ok('撤回手动标记后仍待处理', good(await call(emp,'PUT',path+'/handoff-withdraw',{kind:'manual',version:d.version})) &&
  (await detail()).handled === false && !(await detail()).taskTurns.manual.submittedAt)
d = await detail()
ok('从待办重提手动标记', good(await call(emp,'POST',path+'/pending-completion',{kind:'manual',version:d.version,note:'再次确认'})))
d = await detail()
ok('通过手动标记只给实际提交人留下回执', good(await call(boss,'POST',path+'/task-review',{kind:'manual',decision:'confirm',version:d.version})) &&
  (await detail()).handled === true && (await row(emp)).recentApprovalEvents.some((e) => e.kind === 'manual'))

// ---- 项目验收 ----
ok('员工提交验收并写交付说明', good(await call(emp, 'PUT', '/jiedan/order/status', { id, status: 'review', note: '首页和预约都做完了' })))
d = await detail()
const dv = d.taskTurns.delivery
ok('提交验收记成一条交接：验收人是老板，提交人是员工', dv && dv.requesterId === bossId && dv.submittedBy === staff.id && !!dv.submittedAt && dv.submitNote === '首页和预约都做完了')
ok('员工自己的列表里也看得到这条交接', (await row(emp)).taskTurns.delivery.requesterName === d.taskTurns.delivery.requesterName)
ok('本次交付说明存在内部记录', d.timeline.some((e) => e.type === 'delivery' && e.content === '首页和预约都做完了'))
ok('项目返工拒绝旧页面的版本', denied(await call(boss,'POST',path+'/return',{reason:'旧版本',version:d.version-1})))
ok('原提交人撤回项目验收：恢复进行中并保留可再提交的交接', good(await call(emp,'PUT',path+'/handoff-withdraw',{kind:'delivery',version:d.version})) &&
  (await detail()).status === 'doing' && (await detail()).taskTurns.delivery != null && !(await detail()).taskTurns.delivery.submittedAt)
d = await detail()
ok('新版提交使用旧版本时被拒', denied(await call(emp,'PUT','/jiedan/order/status',{id,status:'review',version:d.version-1})))
ok('重新提交时一条内部交付记录可同时携带说明和本轮附件',
  good(await call(emp,'PUT','/jiedan/order/status',{id,status:'review',version:d.version,note:'补了第二版',attachments:[{type:'file',name:'v2.txt',url:'/profile/upload/v2.txt'}]})) &&
  (await detail()).timeline.some((e) => e.type === 'delivery' && e.content === '补了第二版' && e.attachments.some((f) => f.name === 'v2.txt')))
d = await detail()
ok('员工不能退回返工', denied(await call(emp, 'POST', path + '/return', { reason: '乱点' })))
ok('老板退回返工并写原因', good(await call(boss, 'POST', path + '/return', { reason: '预约表单提交后没有提示，补上再交' })))
d = await detail()
ok('退回原因跟着这条交接：状态回到进行中', d.status === 'doing' && d.taskTurns.delivery.returnNote === '预约表单提交后没有提示，补上再交' &&
  !!d.taskTurns.delivery.returnedAt && !d.taskTurns.delivery.submittedAt && d.taskTurns.delivery.submitNote == null)
ok('员工的列表里带着退回原因', (await row(emp)).taskTurns.delivery.returnNote === '预约表单提交后没有提示，补上再交')
ok('员工改完再提交验收', good(await call(emp, 'PUT', '/jiedan/order/status', { id, status: 'review' })))
d = await detail()
ok('再提交后仍带着上次的退回记录', !!d.taskTurns.delivery.submittedAt && !!d.taskTurns.delivery.returnedAt && d.taskTurns.delivery.submitNote == null)
ok('老板不走退回返工、直接手动改回进行中：这条交接作废', good(await call(boss, 'PUT', '/jiedan/order/status', { id, status: 'doing' })) && !(await detail()).taskTurns?.delivery)
ok('老板手动设成待验收：没有提交人，不记交接', good(await call(boss, 'PUT', '/jiedan/order/status', { id, status: 'review' })) && !(await detail()).taskTurns?.delivery)
ok('回到进行中', good(await call(boss, 'PUT', '/jiedan/order/status', { id, status: 'doing' })))
ok('员工再提交验收', good(await call(emp, 'PUT', '/jiedan/order/status', { id, status: 'review' })) && !!(await detail()).taskTurns.delivery.submittedAt)
ok('老板验收通过后交接清掉', good(await call(boss, 'PUT', '/jiedan/order/status', { id, status: 'done' })) &&
  Number(sql(`select count(*) from jiedan_task_turn where order_id=${id}`)) === 0)
ok('已完成的项目退回返工：重新记一条带原因的交接', good(await call(boss, 'POST', path + '/return', { reason: '客户说还要改 logo' })) &&
  (await detail()).taskTurns.delivery.returnNote === '客户说还要改 logo')

// ---- 老板自己负责的项目不记交接 ----
const own = (await call(boss, 'POST', '/jiedan/order', { title: '交接说明-自己的-' + tag, channel: 'wechat', ownerId: bossId })).data.id
await call(boss, 'PUT', '/jiedan/order/status', { id: own, status: 'doing' })
ok('老板自己的项目设成待验收不记交接', good(await call(boss, 'PUT', '/jiedan/order/status', { id: own, status: 'review' })) &&
  Number(sql(`select count(*) from jiedan_task_turn where order_id=${own}`)) === 0)
ok('老板退回自己的项目也不记交接', good(await call(boss, 'POST', '/jiedan/order/' + own + '/return', { reason: '自己再改改' })) &&
  Number(sql(`select count(*) from jiedan_task_turn where order_id=${own}`)) === 0)

// ---- 清理 + 升级脚本可重复执行 ----
for (const x of [id, own]) { await call(boss, 'DELETE', '/jiedan/order/' + x); await call(boss, 'DELETE', `/jiedan/order/${x}/purge`) }
for (let i = 0; i < 2; i++) ok('交接说明升级脚本可重复执行', sqlFile(fs.readFileSync(DEPLOY_DIR + '/upgrade_handoff_notes.sql', 'utf8')).status === 0)
ok('只在交接事项表增加一列', Number(sql("select count(*) from information_schema.columns where table_schema=database() and column_name='submit_note' and table_name='jiedan_task_turn'")) === 1)
finish()
