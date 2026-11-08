// 企业微信通知：设置权限与遮挡、发消息（用本地模拟的企业微信接口）、令牌缓存、错误提示、回调地址校验。
// 后端由 run.sh 以 --jiedan.wecom.api-base=http://127.0.0.1:$E2E_WECOM_PORT 启动，这里在该端口起一个模拟服务。
import http from 'node:http'
import crypto from 'node:crypto'
import { ok, call, login, good, denied, sql, section, finish, BASE } from './lib.mjs'

const PORT = Number(process.env.E2E_WECOM_PORT || 18199)
const sends = []
let tokenCalls = 0
let holdNextCard = false
let finishHeldCard
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x')
  const json = (o) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(o)) }
  if (url.pathname === '/cgi-bin/gettoken') {
    tokenCalls++
    const corp = url.searchParams.get('corpid'), secret = url.searchParams.get('corpsecret')
    if (corp === 'wwipblocked') return json({ errcode: 60020, errmsg: 'not allow to access from your ip, hint: [x], from ip: 203.0.113.7' })
    if (corp !== 'wwtestcorp01' || secret !== 'Secret_Test_123456') return json({ errcode: 40013, errmsg: 'invalid corpid' })
    return json({ errcode: 0, access_token: 'TOKEN-' + tokenCalls, expires_in: 7200 })
  }
  if (url.pathname === '/cgi-bin/message/send') {
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', () => {
      const msg = JSON.parse(body)
      sends.push({ token: url.searchParams.get('access_token'), msg })
      if (msg.touser === 'nobody') return json({ errcode: 0, errmsg: 'ok', invaliduser: 'nobody' })
      if (holdNextCard && msg.news?.articles[0].title.startsWith('待处理提醒')) {
        holdNextCard = false
        finishHeldCard = () => json({ errcode: 0, errmsg: 'ok' })
        return
      }
      json({ errcode: 0, errmsg: 'ok' })
    })
    return
  }
  res.statusCode = 404
  res.end()
})
await new Promise((r) => server.listen(PORT, '127.0.0.1', r))
const waitFor = async (pred, ms = 4000) => { const t = Date.now(); while (Date.now() - t < ms) { if (pred()) return true; await new Promise((r) => setTimeout(r, 100)) } return pred() }

section('企业微信通知')
const boss = await login('admin', 'admin123'); ok('管理员登录', !!boss)
const bossId = Number(sql("select user_id from sys_user where user_name='admin'"))
const tag = Date.now().toString().slice(-8), pw = 'Wecom12345'
ok('创建员工', good(await call(boss, 'POST', '/jiedan/staff', { userName: 'wx' + tag, nickName: '企微员工', password: pw })))
const emp = (await call(boss, 'GET', '/jiedan/staff/list')).data.find((s) => s.userName === 'wx' + tag)
const te = await login('wx' + tag, pw)
const save = (t, cfg) => call(t, 'POST', '/jiedan/push/config', cfg)

// ---- 设置：格式、权限、遮挡 ----
ok('企业 ID 格式不对被拒', denied(await save(boss, { 'push.wecom.corpId': 'ww 坏的' })))
ok('AgentId 不是数字被拒', denied(await save(boss, { 'push.wecom.agentId': 'abc' })))
ok('EncodingAESKey 长度不对被拒', denied(await save(boss, { 'push.wecom.aesKey': 'short' })))
ok('员工不能改企业微信应用配置', denied(await save(te, { 'push.wecom.corpId': 'wwtestcorp01' })))
ok('员工不能改别人的企业微信账号', denied(await save(te, { ['push.wecom.user.' + bossId]: 'hacker' })))
ok('员工可以填自己的企业微信账号', good(await save(te, { ['push.wecom.user.' + emp.id]: 'ZhangSan' })))
const aesKey = crypto.randomBytes(32).toString('base64').replace(/=+$/, '').replace(/[+/]/g, 'A').slice(0, 43)
ok('老板保存企业微信配置', good(await save(boss, {
  'push.enabled': 'true', 'push.siteUrl': 'https://jiedan.example.com',
  'push.wecom.corpId': 'wwtestcorp01', 'push.wecom.agentId': '1000002', 'push.wecom.secret': 'Secret_Test_123456',
  'push.wecom.token': 'TokenAbc123', 'push.wecom.aesKey': aesKey, ['push.wecom.user.' + bossId]: 'LaoBan'
})))
const cfgBoss = (await call(boss, 'GET', '/jiedan/push/config')).data
ok('Secret 与 EncodingAESKey 不回传原文', cfgBoss['push.wecom.secret'] === '********' && cfgBoss['push.wecom.aesKey'] === '********' && cfgBoss['push.wecom.corpId'] === 'wwtestcorp01')
const cfgEmp = (await call(te, 'GET', '/jiedan/push/config')).data
ok('员工看不到企业微信应用配置和别人的账号', !('push.wecom.secret' in cfgEmp) && !('push.wecom.corpId' in cfgEmp) && !(('push.wecom.user.' + bossId) in cfgEmp) && cfgEmp['push.wecom.user.' + emp.id] === 'ZhangSan')
ok('原样传回遮挡值不会覆盖 Secret', good(await save(boss, { 'push.wecom.secret': '********' })))

// ---- 发送测试 ----
let r = await call(boss, 'POST', '/jiedan/push/test', { memberId: bossId })
ok('发送测试走企业微信', good(r) && String(r.data ?? r.msg).includes('已通过企业微信发送'), JSON.stringify(r))
const first = sends.at(-1)?.msg
ok('整张通知是单条可点击消息', first?.touser === 'LaoBan' && first?.agentid === 1000002 && first?.msgtype === 'news' && first.news.articles.length === 1)
const base = 'https://jiedan.example.com/#/'
ok('测试消息打开工作台', first?.news?.articles[0].url === base + 'workbench')

const delivered = (to, title, path) => waitFor(() => sends.some(({msg}) =>
  msg.touser === to && msg.news?.articles.some(a => a.title.includes(title) && a.url === base + path)))
const customer = 'wc' + tag
await call(boss,'POST','/jiedan/customer',{userName:customer,nickName:'问题反馈人',password:pw,ownerId:emp.id})
const customerToken = await login(customer,pw)
const id = (await call(boss, 'POST', '/jiedan/order', { title: '企微通知-' + tag, channel: 'wechat', ownerId: emp.id, customerAccount:customer })).data.id
const project = 'order?id=' + id
ok('派单打开对应项目', await delivered('ZhangSan', '新单待接', project))
ok('发起催办', good(await call(boss, 'POST', '/jiedan/order/nudge', { id })))
ok('催办打开对应项目', await delivered('ZhangSan', '催办', project))
const deadline = '2028-02-29 14:30'
const setUrgency = await call(boss, 'PUT', '/jiedan/order/' + id + '/urgency', { urgentUntil: deadline, previousUntil: null })
const remind = (who, version) => call(who, 'POST', '/jiedan/order/nudge', {id, kind:'urgent', version})
ok('加急提醒只允许向其他项目负责人发出', good(setUrgency) && denied(await remind(te, setUrgency.data.version)) && denied(await remind(customerToken, setUrgency.data.version)))
const nudges = await Promise.all([remind(boss, setUrgency.data.version), remind(boss, setUrgency.data.version)])
ok('同时点击加急提醒只创建一条待收通知', nudges.filter(good).length === 1 && nudges.some(r=>r.code===409))
ok('加急通知送达指定负责人并打开原项目', await delivered('ZhangSan','加急提醒',project))
const urgentCards = sends.map(s=>s.msg).filter(m=>m.news?.articles.some(a=>a.title.includes('加急提醒') && a.url===base+project))
ok('微信卡片显示项目名与精确到分钟的截止时间且只发给负责人', urgentCards.length===1 && urgentCards[0].touser==='ZhangSan' && urgentCards[0].news.articles[0].title.includes(tag) && urgentCards[0].news.articles[0].description.includes(deadline))
const reminded = (await call(te, 'GET', '/jiedan/order/' + id)).data
ok('负责人在系统内也看到未读与内部记录', reminded.unread.includes(emp.id) && reminded.timeline.some(t=>t.content.includes(deadline)))
const handled = await call(boss, 'PUT', '/jiedan/order/' + id + '/handling', {handled:true,version:reminded.version})
ok('已处理的加急项目不可再催办', good(handled) && denied(await remind(boss, handled.data.version)))
const newBug = await call(boss,'POST','/jiedan/order/bug',{orderId:id,content:'卡片定位测试'})
const bug = newBug.data.bugs[0].id
const issue = project + '&tab=bugs&bug=' + bug
ok('新 Bug 打开对应问题', await delivered('ZhangSan','新问题',issue))
const bugHeading = '新问题 #' + bug + ' · 企微通知-' + tag
ok('快速创建后本人也收到带项目名和问题编号的卡片', await delivered('LaoBan',bugHeading,issue))
const issueCards = path => sends.map(s=>s.msg).filter(msg => msg.news?.articles[0].url === base+path)
ok('卡片显示实际问题说明，创建状态仍为未标记',
  issueCards(issue).every(msg=>msg.news.articles[0].description.includes('卡片定位测试')) && newBug.data.bugs[0].status==='open')
const imageOnly = await call(te,'POST','/jiedan/order/bug',{orderId:id,attachments:[{name:'现场截图.png',type:'image',url:'/jiedan/media/'+'a'.repeat(32)+'.png'}]})
const imageBug = imageOnly.data.bugs.find(item=>item.id!==bug)
const imagePath = project+'&tab=bugs&bug='+imageBug.id
ok('项目负责人自己提交附件也能点回该问题', await delivered('ZhangSan','新问题 #'+imageBug.id,imagePath))
ok('附件问题说明和接收人正确', await delivered('LaoBan','新问题 #'+imageBug.id,imagePath) &&
  issueCards(imagePath).every(msg=>msg.news.articles[0].description.includes('请查看问题附件')))
await call(boss,'PUT','/jiedan/order/bug/'+bug+'/status',{status:'action'})
ok('交办通知打开同一问题', await delivered('ZhangSan','问题需处理',issue))
await call(te,'PUT','/jiedan/order/bug/'+bug+'/status',{status:'resolved'})
await Promise.all(['open','reopen'].map(status => call(customerToken,'PUT','/jiedan/portal/bug/'+bug+'/status',{status})))
ok('客户重新激活时，项目负责人和管理人收到同一问题的链接', await delivered('ZhangSan','客户重新激活了问题',issue) && await delivered('LaoBan','客户重新激活了问题',issue))
const reopenRecipients = issueCards(issue).filter(msg=>msg.news.articles[0].title==='客户重新激活了问题').map(msg=>msg.touser).sort()
ok('重复请求不会重复推送重新激活通知', JSON.stringify(reopenRecipients)===JSON.stringify(['LaoBan','ZhangSan']))
ok('只能由管理账号配置第二个报价微信',denied(await save(te,{['push.wecom.quoteCopy.'+bossId]:'Other'})))
ok('不能把第二个微信设置成群发',denied(await save(boss,{['push.wecom.quoteCopy.'+bossId]:'@all'})))
ok('设置第二个报价微信',good(await save(boss,{['push.wecom.quoteCopy.'+bossId]:'OwnerTwo'})))
const quote = (await call(boss,'POST','/jiedan/quote',{employeeId:emp.id,title:'报价直达',requirement:'附件与需求'})).data
ok('报价邀请打开指定报价单', await delivered('ZhangSan','新需求待报价','quotes?id='+quote.id))
ok('邀请同时通知发起人的两个微信',await delivered('LaoBan','报价邀请已发出','quotes?id='+quote.id) && await delivered('OwnerTwo','报价邀请已发出','quotes?id='+quote.id))
const priced = (await call(te,'PUT','/jiedan/quote/'+quote.id+'/price',{amount:60,version:quote.version})).data
ok('提交报价提醒打开同一报价单', await delivered('LaoBan','收到新报价','quotes?id='+quote.id))
ok('创建人与负责人或管理账号重合时只收一条新 Bug 卡片',
  issueCards(imagePath).filter(msg=>msg.touser==='ZhangSan').length===1 &&
  issueCards(issue).filter(msg=>msg.touser==='LaoBan' && msg.news.articles[0].title===bugHeading).length===1)
ok('结果也通知发起人的第二个微信',await delivered('OwnerTwo','收到新报价','quotes?id='+quote.id))
const quotePath='/jiedan/quote/'+quote.id
const receipt=q=>[{id:q.id,noticeVersion:q.noticeVersion}]
const current=async t=>(await call(t,'GET',quotePath)).data
ok('已报价通知的发起人最初未读', priced.noticeVersion>0 && (await current(boss)).readVersion<priced.noticeVersion)
await call(boss,'PUT','/jiedan/quote/read',receipt(priced))
ok('已读状态在再次获取时保留', Number((await current(boss)).readVersion)===Number(priced.noticeVersion))
const revised={title:'移动端二期',requirement:'新需求：支持在线支付',attachments:[],version:priced.version,requestPrice:false}
ok('报价人不能改发起方的需求',denied(await call(te,'PUT',quotePath,revised)))
const quiet=(await call(boss,'PUT',quotePath,revised)).data
ok('只保存需求会保留原报价及已读状态', quiet.status==='quoted' && Number(quiet.amount)===60 && quiet.noticeVersion===quiet.readVersion)
const invited=(await call(boss,'PUT',quotePath,{...revised,version:quiet.version,requestPrice:true})).data
ok('修改后向原报价人发送新卡片', await delivered('ZhangSan','请按最新需求报价','quotes?id='+quote.id))
ok('对方打开原单可见新名称、内容和当前待报价状态', invited.status==='pending' && (await current(te)).requirement===revised.requirement && invited.title===revised.title)
ok('旧页面无法覆盖新需求',denied(await call(te,'PUT',quotePath+'/price',{amount:10,version:priced.version})))
await call(te,'PUT','/jiedan/quote/read',receipt(invited))
ok('报价人标记已读不完成报价任务', (await current(te)).readVersion===invited.noticeVersion && (await current(te)).status==='pending')
const oldCardCount=sends.filter(({msg})=>msg.touser==='LaoBan' && msg.news?.articles[0].title==='收到新报价').length
const second=(await call(te,'PUT',quotePath+'/price',{amount:120,version:invited.version})).data
ok('按新需求报价后发起人收到结果微信卡片',await waitFor(()=>sends.filter(({msg})=>msg.touser==='LaoBan' && msg.news?.articles[0].title==='收到新报价' && msg.news.articles[0].url===base+'quotes?id='+quote.id).length>oldCardCount))
await call(boss,'PUT','/jiedan/quote/read',receipt(priced))
ok('旧回执不会清掉刚到的新报价红点',(await current(boss)).readVersion<second.noticeVersion)
const chat=quotePath+'/conversation'
const say=(t,k,content='',files=[])=>call(t,'POST',chat,{employeeId:emp.id,clientKey:k,content,attachments:files})
const records=async(t)=>(await call(t,'GET',chat)).data.items
ok('初次和修改金额保留为两轮报价',(await records(boss)).map(m=>Number(m.amount)).join(',')==='60,120')
ok('未登录用户和空回复不能发言',denied(await say(null,'bad-user-test','bad')) && denied(await say(te,'empty-reply-key','  ')))
const ask=(await say(boss,'ask-question-0001','这个价格包含部署吗？')).data
ok('发起人可在已报价后继续追问，报价人微信直达沟通',!!ask.id && await delivered('ZhangSan','报价有新回复','quotes?id='+quote.id+'&tab=chat'))
const retries=await Promise.all([say(boss,'ask-question-0001','这个价格包含部署吗？'),say(boss,'ask-question-0001','这个价格包含部署吗？')])
ok('重复发送不重复入库与推送',retries.every(x=>x.data.id===ask.id)&&(await records(te)).filter(m=>m.id===ask.id).length===1 && issueCards('quotes?id='+quote.id+'&tab=chat').filter(m=>m.touser==='ZhangSan').length===1)
const pic={type:'image',name:'说明图.png',url:'/jiedan/media/'+'a'.repeat(32)+'.png',ext:'png',size:12}
const ans=(await say(te,'answer-question-01','包含部署，另附参考图',[pic])).data
ok('报价人可附图回复，两个微信都能点回对话',!!ans.id && await delivered('LaoBan','报价有新回复','quotes?id='+quote.id+'&tab=chat') && await delivered('OwnerTwo','报价有新回复','quotes?id='+quote.id+'&tab=chat'))
const ask2=(await say(boss,'ask-question-0002','工期多少天？')).data
const ans2=(await say(te,'answer-question-02','大约五天')).data
const convo=await records(boss)
ok('两轮对话按顺序保留，说话人和附件正确',convo.filter(m=>m.kind==='reply').map(m=>m.id).join() === [ask.id,ans.id,ask2.id,ans2.id].join() && convo.find(m=>m.id===ans.id).attachments[0].url===pic.url && convo.find(m=>m.id===ans.id).authorId===emp.id)
const after=await current(boss)
ok('交流不改报价金额、状态或编辑版本',after.status==='quoted' && Number(after.amount)===120 && after.version===second.version)
ok('双方各自未读独立于报价状态',after.replyVersion===ans2.id && (await current(te)).replyVersion===ask2.id)
await call(boss,'PUT','/jiedan/quote/read',[{id:quote.id,noticeVersion:after.noticeVersion,replyVersion:ans.id}])
ok('过期的已读回执保留后到的新回复',(await current(boss)).replyVersion===ans2.id)
await call(boss,'PUT','/jiedan/quote/read',[{id:quote.id,noticeVersion:after.noticeVersion,replyVersion:ans2.id}])
ok('发起人已读不会清掉报价人的提醒',(await current(boss)).replyVersion===0 && (await current(te)).replyVersion===ask2.id)
const partial=(await call(boss,'GET',chat+'?before='+ask2.id)).data
ok('向前翻页不重复边界且按时间排序',partial.items.at(-1).id===ans.id)
const peerName='wx2'+tag
await call(boss,'POST','/jiedan/staff',{userName:peerName,nickName:'另一个报价人',password:pw})
const peer=(await call(boss,'GET','/jiedan/staff/list')).data.find(s=>s.userName===peerName)
await save(boss,{['push.wecom.user.'+peer.id]:'ReceiverTwo'})
const moved={...revised,version:second.version,employeeId:peer.id}
ok('不能静默将需求改派给别人',denied(await call(boss,'PUT',quotePath,moved)))
const mark=sends.length
const changed=(await call(boss,'PUT',quotePath,{...moved,requestPrice:true})).data
ok('编辑并改派只向新报价人发送操作邀请',await delivered('ReceiverTwo','请按最新需求报价','quotes?id='+quote.id) && !sends.slice(mark).some(({msg})=>msg.touser==='ZhangSan' && msg.news?.articles[0]?.title==='请按最新需求报价'))
ok('改派不带入其他人的金额，原报价人失去访问权',changed.employeeId===peer.id && changed.amount==null && denied(await call(te,'GET',quotePath)))
const tp=await login(peerName,pw)
ok('改派后历史只对原发起人保留，新人看不到旧报价往来',(await records(boss)).length===6 && (await records(tp)).length===0 && denied(await call(te,'GET',chat)))
ok('旧页面不能误发给新报价人',denied(await say(boss,'stale-old-person','发给旧报价人')))
await call(boss,'DELETE',quotePath,{version:changed.version})
ok('删除报价后不能查看或补发回复',denied(await call(boss,'GET',chat)) && denied(await say(boss,'deleted-quote-key','x')))
// 待处理卡片上的提醒只能发给该事项的实际负责人。
const pendingPath = '/jiedan/order/' + id
const getVersion = async () => (await call(boss,'GET',pendingPath)).data.version
const notify = async (who, kind, args = {}, version) => call(who,'POST',pendingPath+'/pending-reminder',
  {kind,version:version ?? await getVersion(),...args})
const receipts = async () => (await call(boss,'GET',pendingPath)).data.reminderReceipts
const listReceipts = async who => (await call(who,'GET','/jiedan/order/list')).data.find(row=>row.id===id).reminderReceipts
const cards = () => sends.map(s=>s.msg).filter(msg=>msg.news?.articles[0]?.title === '待处理提醒 · 企微通知-'+tag)
const checkCard = (recipient, path, detail) => {
  const msg = cards().at(-1)
  return msg?.touser === recipient && msg.news.articles[0].url === base+path && msg.news.articles[0].description.includes(detail)
}
ok('非项目成员及客户不能发待处理提醒',denied(await notify(tp,'urgent')) && denied(await notify(customerToken,'urgent')))
ok('不能催办已处理的加急、无手动标记或不存在的问题',denied(await notify(boss,'urgent')) &&
  denied(await notify(boss,'manual')) && denied(await notify(boss,'bug',{bugId:99999999})) && cards().length===0)
await call(boss,'PUT',pendingPath+'/next',{content:'确认设计稿',ownerId:bossId,due:'2028-02-29'})
const version = await getVersion()
const duplicate = await Promise.all([notify(te,'next',{},version),notify(te,'next',{},version)])
ok('两次同时点击只发一张下一步微信卡片',duplicate.filter(good).length===1 && duplicate.some(x=>x.code===409) && cards().length===1)
ok('下一步只提醒单独指定的人并打开项目',checkCard('LaoBan',project,'确认设计稿') && checkCard('LaoBan',project,'2028-02-29'))
const firstReceipt = (await receipts()).next
ok('详情与另一账号的列表显示相同的发送人和服务端时间',firstReceipt?.by === '企微员工' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(firstReceipt.at) &&
  JSON.stringify((await listReceipts(te)).next)===JSON.stringify(firstReceipt))
ok('客户项目不包含内部提醒回执',(await call(customerToken,'GET','/jiedan/portal/orders/'+id)).data.reminderReceipts===undefined)
await call(boss,'PUT',pendingPath+'/handling',{handled:false,version:await getVersion()})
ok('手动未处理提醒打开项目处理状态',good(await notify(boss,'manual')) && checkCard('ZhangSan',project+'&followup=manual','未处理'))
ok('提醒自己的加急也只发给自己且含截止时间',good(await notify(te,'urgent')) && checkCard('ZhangSan',project,deadline))
await call(boss,'PUT','/jiedan/order/bug/'+bug+'/status',{status:'action'})
ok('问题提醒包含对应的说明、编号并直达该问题',good(await notify(boss,'bug',{bugId:bug})) &&
  checkCard('ZhangSan',issue,'卡片定位测试') && checkCard('ZhangSan',issue,'#'+bug))
await call(boss,'PUT','/jiedan/order/bug/'+bug+'/status',{status:'resolved'})
const afterResolution = await receipts()
ok('解决问题只清除本问题回执，已提醒的其他待办仍可见',!afterResolution['bug-'+bug] && afterResolution.manual && afterResolution.urgent && afterResolution.next)
await call(customerToken,'PUT','/jiedan/portal/bug/'+bug+'/status',{status:'reopen'})
ok('重新激活的问题从未提醒开始',!(await listReceipts(te))['bug-'+bug])
await call(boss,'PUT','/jiedan/order/bug/'+bug+'/status',{status:'resolved'})
const beforeInvalid = cards().length
ok('已解决的问题、无效类型、无效编号均不发送微信',denied(await notify(boss,'bug',{bugId:bug})) &&
  denied(await notify(boss,'x')) && denied(await notify(boss,'bug',{bugId:'invalid'})) && cards().length===beforeInvalid)
await save(boss,{['push.wecom.user.'+emp.id]:''})
ok('未绑定时明确报错且无微信请求',(await notify(boss,'manual')).msg.includes('绑定') && cards().length===beforeInvalid)
const previousManual = (await receipts()).manual
await save(boss,{['push.wecom.user.'+emp.id]:'nobody'})
ok('企业微信拒收时不返回成功',(await notify(boss,'manual')).msg.includes('找不到账号'))
ok('发送失败不会覆盖上一次成功回执',JSON.stringify((await receipts()).manual)===JSON.stringify(previousManual))
await save(boss,{['push.wecom.user.'+emp.id]:'ZhangSan'})
ok('绑定修正后可重发给原负责人',good(await notify(boss,'manual')) && checkCard('ZhangSan',project+'&followup=manual','未处理'))
await call(boss,'PUT',pendingPath+'/next',{content:''})
ok('已清除的下一步不再发送',denied(await notify(te,'next')))
ok('清除下一步后列表立即移除旧回执',!(await listReceipts(te)).next)
await call(boss,'PUT',pendingPath+'/next',{content:'新稿',ownerId:bossId})
holdNextCard=true
const late = notify(te,'next')
try {
  ok('模拟企业微信回执延迟',await waitFor(()=>!!finishHeldCard))
  await call(boss,'PUT',pendingPath+'/next',{content:'新稿',ownerId:emp.id})
  ok('改派后新的负责人可立即获得自己的回执',good(await notify(boss,'next')))
} finally { if (finishHeldCard) finishHeldCard() }
ok('晚到的旧回执不会覆盖改派后的提醒',good(await late) && (await receipts()).next.by !== firstReceipt.by)
await call(boss,'PUT',pendingPath+'/urgency',{urgentUntil:'2028-03-01 17:00',previousUntil:deadline})
ok('变更加急截止时间后该项重新待提醒，其他记录保留',!(await receipts()).urgent && !!(await receipts()).manual)
ok('令牌被缓存（只取了一次）', tokenCalls === 1, 'tokenCalls=' + tokenCalls)

// ---- 错误提示 ----
await save(boss, { ['push.wecom.user.' + bossId]: 'nobody' })
r = await call(boss, 'POST', '/jiedan/push/test', { memberId: bossId })
ok('账号不对时说清楚', String(r.data ?? r.msg).includes('找不到账号「nobody」'), JSON.stringify(r))
await save(boss, { 'push.wecom.corpId': 'wwipblocked', ['push.wecom.user.' + bossId]: 'LaoBan' })
r = await call(boss, 'POST', '/jiedan/push/test', { memberId: bossId })
ok('IP 不在白名单时告诉要加哪个 IP', String(r.data ?? r.msg).includes('203.0.113.7'), JSON.stringify(r))

// ---- 回调地址校验（企业微信加解密方案，按官方说明独立实现一遍）----
await save(boss, { 'push.wecom.corpId': 'wwtestcorp01' })
function encrypt(text, corpId) {
  const key = Buffer.from(aesKey + '=', 'base64')
  const msg = Buffer.from(text, 'utf8')
  const len = Buffer.alloc(4); len.writeUInt32BE(msg.length)
  let plain = Buffer.concat([crypto.randomBytes(16), len, msg, Buffer.from(corpId, 'utf8')])
  const pad = 32 - (plain.length % 32)
  plain = Buffer.concat([plain, Buffer.alloc(pad, pad)])
  const c = crypto.createCipheriv('aes-256-cbc', key, key.subarray(0, 16)); c.setAutoPadding(false)
  return Buffer.concat([c.update(plain), c.final()]).toString('base64')
}
const sign = (token, ts, nonce, enc) => crypto.createHash('sha1').update([token, ts, nonce, enc].sort().join('')).digest('hex')
const echo = 'echo-' + tag
const enc = encrypt(echo, 'wwtestcorp01'), ts = String(Math.floor(Date.now() / 1000)), nonce = 'n' + tag
const q = (sig, e = enc) => `${BASE}/jiedan/wecom/callback?msg_signature=${sig}&timestamp=${ts}&nonce=${nonce}&echostr=${encodeURIComponent(e)}`
let res = await fetch(q(sign('TokenAbc123', ts, nonce, enc)))
ok('回调地址校验通过并返回明文', res.status === 200 && (await res.text()) === echo)
res = await fetch(q('0'.repeat(40)))
ok('签名不对被拒', res.status === 403 && (await res.text()) === '')
const other = encrypt(echo, 'wwothercorp9')
res = await fetch(q(sign('TokenAbc123', ts, nonce, other), other))
ok('别的企业的密文被拒', res.status === 403)
res = await fetch(`${BASE}/jiedan/wecom/callback`, { method: 'POST', body: '<xml></xml>' })
ok('收到成员消息时回空串', res.status === 200 && (await res.text()) === '')

// ---- 收尾：清掉企业微信配置，不影响其他测试 ----
await save(boss, { 'push.wecom.corpId': '', 'push.wecom.agentId': '', 'push.wecom.secret': '', 'push.wecom.token': '', 'push.wecom.aesKey': '', ['push.wecom.user.' + bossId]: '', ['push.wecom.user.' + emp.id]: '', 'push.siteUrl': '' })
await call(boss, 'DELETE', '/jiedan/order/' + id)
server.close()
finish()
