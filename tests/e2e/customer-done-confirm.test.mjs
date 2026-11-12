// 客户提的问题：团队做完是「已处理」，发给客户确认，客户点「确认已解决」才是已解决。
// AI（Codex）改完的回报以项目负责人的名义发给客户，测试结果只给团队看。
import { randomUUID } from 'node:crypto'
import { ok, call, raw, login, good, denied, sql, section, finish } from './lib.mjs'

section('客户提的问题：已处理 → 客户确认 → 已解决')
const boss = await login('admin', 'admin123'); ok('管理员登录', !!boss)
const cust = await login('cust_a', 'customer123'); ok('客户登录', !!cust)
const tag = Date.now().toString().slice(-8), pw = 'Done12345'
ok('创建员工', good(await call(boss, 'POST', '/jiedan/staff', { userName: 'dc' + tag, nickName: '处理员工', password: pw })))
const staff = (await call(boss, 'GET', '/jiedan/staff/list')).data.find((s) => s.userName === 'dc' + tag)
const emp = await login(staff.userName, pw)
const id = (await call(boss, 'POST', '/jiedan/order', { title: '已处理-' + tag, channel: 'wechat', ownerId: staff.id, customerAccount: 'cust_a' })).data.id
sql('update jiedan_order set customer_account=\'cust_a\' where id=' + id)
const path = '/jiedan/order/' + id
ok('员工接收并开工', good(await call(emp, 'POST', path + '/accept')) && good(await call(emp, 'PUT', '/jiedan/order/status', { id, status: 'doing' })))
const detail = async (who = boss) => (await call(who, 'GET', path)).data
const portal = async () => (await call(cust, 'GET', '/jiedan/portal/orders/' + id)).data
const issue = (vo, bugId) => vo.bugs.find((b) => b.id === bugId)
const customerIssue = async (content) => {
  const vo = (await call(cust, 'POST', '/jiedan/portal/bug', { orderId: id, content })).data
  return vo.bugs.find((b) => b.content === content).id
}
const status = (bugId, body, who = emp) => call(who, 'PUT', '/jiedan/order/bug/' + bugId + '/status', body)

// ---- 团队直接点「已解决」：变成已处理，等客户确认 ----
const first = await customerIssue('首页按钮颜色不对')
let d = await detail()
ok('客户提的问题标着「要客户确认」', issue(d, first).customerConfirm === true)
ok('员工加入处理', good(await status(first, { status: 'action' })))
const row = (await call(emp, 'GET', '/jiedan/order/list')).data.find((o) => o.id === id)
ok('列表的「需处理」预览也带着这个标记', !!row.actionBugPreviews.find((b) => b.id === first)?.customerConfirm)
ok('员工点做完（已解决）', good(await status(first, { status: 'resolved', previousStatus: 'action' })))
d = await detail()
ok('没有直接变成已解决，而是已处理、等客户确认', issue(d, first).status === 'confirm' && issue(d, first).review?.state === 'pending')
ok('发给客户的那条署名是做的人', issue(d, first).updates.at(-1).createdBy === '处理员工' && issue(d, first).updates.at(-1).content === '请确认这次修改')
ok('不再算在团队要处理的里', !(await call(emp, 'GET', '/jiedan/order/list')).data.find((o) => o.id === id).actionBugPreviews?.some((b) => b.id === first))
let p = await portal()
ok('客户那边显示「已处理，等你确认」', issue(p, first).progressStatus === 'confirm' && issue(p, first).review?.state === 'pending')
ok('等客户确认时团队不能再改状态', denied(await status(first, { status: 'resolved' })))
ok('客户确认后才是已解决', good(await call(cust, 'POST', '/jiedan/portal/bug/' + first + '/review/' + issue(p, first).review.id, { decision: 'accept' })) &&
  issue(await detail(), first).status === 'resolved')

// ---- 客户觉得还有问题：回到需处理 ----
const second = await customerIssue('购物车数量能改成 0')
ok('从通知里点「已处理」也是发给客户', good(await status(second, { status: 'action' })) &&
  good(await call(emp, 'POST', path + '/pending-completion', { kind: 'bug', bugId: second, version: (await detail()).version })) &&
  issue(await detail(), second).status === 'confirm')
p = await portal()
ok('客户说还要改，回到需处理', good(await call(cust, 'POST', '/jiedan/portal/bug/' + second + '/review/' + issue(p, second).review.id, { decision: 'adjust', content: '改成 0 还是能下单' })) &&
  issue(await detail(), second).status === 'action')

// ---- 同事交给老板，老板通过：带着同事的说明发给客户 ----
const third = await customerIssue('详情页图片太慢')
ok('员工做完交给管理员确认', good(await status(third, { status: 'submitReview', note: '图片压缩到 200K 以内' })) && issue(await detail(), third).status === 'ai_review')
ok('管理员通过', good(await status(third, { status: 'resolved', previousStatus: 'ai_review' }, boss)))
d = await detail()
ok('通过后是已处理，等客户确认（不是已解决）', issue(d, third).status === 'confirm')
ok('同事写的说明带给客户', issue(d, third).updates.at(-1).content === '请确认修改：图片压缩到 200K 以内')

// ---- 「已处理」窗口里说明和截图都可以不填 ----
const fourth = await customerIssue('标题字太小')
ok('不写说明、不传截图也能发给客户确认', good(await call(emp, 'POST', '/jiedan/order/bug/' + fourth + '/review', { previousReviewId: null, requestKey: randomUUID() })) &&
  issue(await detail(), fourth).status === 'confirm' && issue(await detail(), fourth).updates.at(-1).content === '请确认这次修改')

// ---- 团队自己记的问题、没绑客户账号的项目：照旧直接已解决 ----
const own = (await call(emp, 'POST', '/jiedan/order/bug', { orderId: id, content: '自己发现的：页脚错位' })).data.bugs.find((b) => b.content === '自己发现的：页脚错位').id
ok('团队记的问题不需要客户确认', issue(await detail(), own).customerConfirm === false)
ok('团队记的问题点已解决就是已解决', good(await status(own, { status: 'resolved' })) && issue(await detail(), own).status === 'resolved')
const loose = (await call(boss, 'POST', '/jiedan/order', { title: '没绑客户-' + tag, channel: 'wechat' })).data.id
sql(`insert into jiedan_bug(order_id,content,status,created_by,create_time,sender_kind) values(${loose},'老客户反馈','action','某客户',now(),'customer')`)
const looseBug = Number(sql('select max(id) from jiedan_bug where order_id=' + loose))
ok('没绑客户账号的项目，客户问题也可以直接已解决', good(await call(boss, 'PUT', '/jiedan/order/bug/' + looseBug + '/status', { status: 'resolved' })) &&
  sql('select status from jiedan_bug where id=' + looseBug) === 'resolved')

// ---- AI 回报：以项目负责人的名义发给客户，测试结果只给团队看 ----
section('AI 回报以负责人的名义发给客户')
const generated = await call(boss, 'POST', '/jiedan/agent/orders/' + id + '/link/generate')
ok('生成 AI 只读链接', good(generated))
const key = await call(boss, 'POST', '/jiedan/agent/orders/' + id + '/reply-key/enable')
ok('开启回复权限', good(key) && !!key.data.secret)
const report = (bugId, changes, tests = 'npm test 全部通过') => raw(null, 'POST', '/jiedan/agent/issue-reports/' + bugId,
  { requestId: randomUUID(), changes, tests }, { 'X-Codex-Reply-Key': key.data.secret }).then((r) => r.json())
const fifth = await customerIssue('登录页按钮被键盘挡住')
ok('AI 提交回报', good(await report(fifth, '登录按钮在手机键盘弹出时会自动上移，不再被挡住')))
d = await detail()
const updates = issue(d, fifth).updates
ok('改完就是已处理，等客户确认', issue(d, fifth).status === 'confirm' && issue(d, fifth).review?.state === 'pending')
ok('团队这边看不到「Codex」署名，都是项目负责人', updates.length >= 2 && updates.every((u) => u.createdBy === '处理员工'))
ok('完整回报（含测试结果）留给团队', updates.some((u) => u.senderKind === 'codex' && u.content.includes('测试结果：npm test 全部通过')))
ok('发给客户的是修改内容', updates.at(-1).content === '请确认修改：登录按钮在手机键盘弹出时会自动上移，不再被挡住')
p = await portal()
const seen = issue(p, fifth).updates
ok('客户看到负责人的回复，没有测试结果', seen.some((u) => u.createdBy === '处理员工' && u.content.includes('自动上移')) &&
  !seen.some((u) => u.content.includes('测试结果')) && issue(p, fifth).progressStatus === 'confirm')
const summary = (await call(cust, 'GET', '/jiedan/portal/orders')).data.find((o) => o.id === id).requirements.find((r) => r.id === fifth)
ok('客户首页和消息里写的是负责人的名字', summary.latestTeamReply?.by === '处理员工' && summary.latestTeamReply.content.includes('自动上移'))
const reports = (await call(boss, 'GET', '/jiedan/agent/reports')).data
ok('AI 回复列表里状态是已处理', reports.find((r) => r.bugId === fifth)?.status === 'confirm')
const mine = (await call(emp, 'POST', '/jiedan/order/bug', { orderId: id, content: '自己记的：接口超时' })).data.bugs.find((b) => b.content === '自己记的：接口超时').id
ok('AI 回报团队自己记的问题', good(await report(mine, '把超时改成 10 秒')))
d = await detail()
ok('团队记的问题仍先交给内部验收，署名也是负责人', issue(d, mine).status === 'ai_review' && issue(d, mine).updates.at(-1).createdBy === '处理员工')

finish()
