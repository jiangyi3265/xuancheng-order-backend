// 权限端到端测试：总管理员 / 下属管理员 / 客户 的数据隔离、转派、停用踢线。
// 前提：测试库是 fresh-install.sql + seed.sql（旧版数据：订单负责人 1/2），后端指向该库。用 run.sh 一键跑。
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { ok, call, login, good, denied, sql, sqlFile, section, finish, DEPLOY_DIR } from './lib.mjs'

const REMAP = path.join(DEPLOY_DIR, 'upgrade_permission_v1_owner_remap.sql')

section('A 总管理员登录')
const boss = await login('admin', 'admin123')
ok('admin 登录成功', !!boss)
let me = await call(boss, 'GET', '/jiedan/staff/me')
ok('/staff/me 识别为 boss', good(me) && me.data.role === 'boss' && me.data.roleLabel === '总管理员', JSON.stringify(me))

section('B 创建下属管理员')
let r = await call(boss, 'POST', '/jiedan/staff', { userName: 'staff1', nickName: '费鹏飞', password: 'staff123' })
ok('创建 staff1', good(r), JSON.stringify(r))
r = await call(boss, 'POST', '/jiedan/staff', { userName: 'staff2', nickName: '小李', password: 'staff123' })
ok('创建 staff2', good(r), JSON.stringify(r))
r = await call(boss, 'POST', '/jiedan/staff', { userName: 'staff1', nickName: 'x', password: 'staff123' })
ok('重复账号被拒', denied(r))
r = await call(boss, 'POST', '/jiedan/staff', { userName: 'bad pw', nickName: 'x', password: '12' })
ok('弱密码被拒', denied(r))
let list = await call(boss, 'GET', '/jiedan/staff/list')
ok('boss 看到 3 个后台账号', good(list) && list.data.length === 3, JSON.stringify(list.data?.map(x => x.userName)))
const s1 = list.data.find(x => x.userName === 'staff1').id
const s2 = list.data.find(x => x.userName === 'staff2').id
const bossId = list.data.find(x => x.userName === 'admin').id

section('C 旧订单负责人迁移')
const remapSql = readFileSync(REMAP, 'utf8').replace('请改成下属的登录账号', 'staff1')
let rr = sqlFile(remapSql)
ok('迁移脚本第一次执行成功', rr.status === 0, rr.stderr)
rr = sqlFile(remapSql)
ok('迁移脚本第二次被拒绝', rr.status !== 0 && /禁止重复执行/.test(rr.stderr), rr.stderr)
ok('订单1(旧owner=1)->总管理员', sql('select owner_id from jiedan_order where id=1') == bossId)
ok('订单4(旧owner=1)->总管理员', sql('select owner_id from jiedan_order where id=4') == bossId)
ok('订单2(旧owner=2)->staff1', sql('select owner_id from jiedan_order where id=2') == s1)
ok('订单3(旧owner=2)->staff1', sql('select owner_id from jiedan_order where id=3') == s1)
ok('未读已清空', sql("select count(*) from jiedan_order where unread<>''") == '0')
ok('SendKey 迁到总管理员', sql(`select setting_value from jiedan_setting where setting_key='push.sendkey.${bossId}'`) === 'KEY_BOSS')
ok('SendKey 迁到 staff1', sql(`select setting_value from jiedan_setting where setting_key='push.sendkey.${s1}'`) === 'KEY_STAFF')
ok('staff1 上级=总管理员', sql(`select owner_user_id from jiedan_account_owner where user_id=${s1}`) == bossId)

section('D 总管理员看全部')
let ol = await call(boss, 'GET', '/jiedan/order/list')
ok('boss 看到 4 个订单', good(ol) && ol.data.length === 4, JSON.stringify(ol.data?.map(o => o.id)))

section('E 下属管理员 staff1 只看自己的')
const t1 = await login('staff1', 'staff123')
ok('staff1 登录成功', !!t1)
me = await call(t1, 'GET', '/jiedan/staff/me')
ok('/staff/me 识别为 employee', good(me) && me.data.role === 'employee' && me.data.id === s1)
ol = await call(t1, 'GET', '/jiedan/order/list')
const ids1 = (ol.data || []).map(o => o.id).sort()
ok('staff1 只看到订单 2,3', good(ol) && JSON.stringify(ids1) === '[2,3]', JSON.stringify(ids1))
ok('staff1 看不到总管理员的订单 1/4', !ids1.includes(1) && !ids1.includes(4))
ok('staff1 读自己的订单 2', good(await call(t1, 'GET', '/jiedan/order/2')))
for (const [name, method, url, body] of [
  ['读详情 1', 'GET', '/jiedan/order/1'],
  ['读版本 1', 'GET', '/jiedan/order/1/version'],
  ['读记事本 1', 'GET', '/jiedan/order/notes/1'],
  ['写记事本 1', 'PUT', '/jiedan/order/notes', { id: 1, notes: 'hack' }],
  ['发进度 1', 'POST', '/jiedan/order/progress', { orderId: 1, content: 'hack', type: 'note' }],
  ['改状态 1', 'PUT', '/jiedan/order/status', { id: 1, status: 'done' }],
  ['标记已读 1', 'PUT', '/jiedan/order/read', { id: 1, memberId: bossId }],
  ['改稿 1', 'POST', '/jiedan/order/revision', { id: 1 }],
  ['催一下 1', 'POST', '/jiedan/order/nudge', { id: 1 }],
  ['修改 1', 'PUT', '/jiedan/order', { id: 1, title: 'hack' }],
  ['建 Bug 到 1', 'POST', '/jiedan/order/bug', { orderId: 1, content: 'hack' }],
  ['改 Bug 状态(bug 1 属订单 1)', 'PUT', '/jiedan/order/bug/1/status', { status: 'resolved' }],
  ['追加 Bug QA(bug 1)', 'POST', '/jiedan/order/bug/1/update', { content: 'hack' }],
  ['删 Bug(bug 1)', 'DELETE', '/jiedan/order/bug/1'],
  ['删除订单 4', 'DELETE', '/jiedan/order/4'],
  ['删除订单 1', 'DELETE', '/jiedan/order/1']
]) {
  ok('staff1 不能' + name, denied(await call(t1, method, url, body)))
}
ok('订单 1 没被改动', sql('select title from jiedan_order where id=1') === '老板自己的单' && sql('select status from jiedan_order where id=1') === 'doing')
ok('订单 1 Bug 仍在', sql('select count(*) from jiedan_bug where order_id=1') === '1')
ok('staff1 可操作自己的 Bug(bug 2)', good(await call(t1, 'PUT', '/jiedan/order/bug/2/status', { status: 'resolved' })))

section('F staff1 建单、转派限制、客户绑定')
r = await call(t1, 'POST', '/jiedan/order', { title: 'staff1新单', ownerId: bossId, customer: 'Z', amount: 10 })
ok('staff1 建单成功', good(r), JSON.stringify(r))
ok('staff1 建单即便指定别人，负责人也是自己', good(r) && r.data.ownerId === s1, JSON.stringify(r.data?.ownerId))
const newId = r.data?.id
r = await call(t1, 'PUT', '/jiedan/order', { id: 2, ownerId: bossId })
ok('staff1 不能转派给总管理员', denied(r))
r = await call(t1, 'PUT', '/jiedan/order', { id: 2, ownerId: s1, title: '副总裁的单(改)' })
ok('staff1 提交与现状相同的 ownerId 不报错', good(r), JSON.stringify(r))
r = await call(t1, 'POST', '/jiedan/order', { title: '绑别人的客户', customerAccount: 'cust_a' })
ok('staff1 不能绑定总管理员的客户 cust_a', denied(r), JSON.stringify(r))
r = await call(t1, 'POST', '/jiedan/customer', { userName: 'cust_s1', nickName: '一号客户', password: 'cust123' })
ok('staff1 创建客户 cust_s1', good(r), JSON.stringify(r))
r = await call(t1, 'POST', '/jiedan/order', { title: '绑自己的客户', customerAccount: 'cust_s1', customer: '一号客户' })
ok('staff1 可绑定自己的客户', good(r), JSON.stringify(r))
const cl1 = await call(t1, 'GET', '/jiedan/customer/list')
ok('staff1 只看到自己的客户', good(cl1) && cl1.data.length === 1 && cl1.data[0].userName === 'cust_s1', JSON.stringify(cl1.data?.map(c => c.userName)))
const custA = sql("select user_id from sys_user where user_name='cust_a'")
ok('staff1 不能重置 cust_a 密码', denied(await call(t1, 'PUT', '/jiedan/customer/reset', { userId: Number(custA), password: 'hack123' })))
ok('staff1 不能删除 cust_a', denied(await call(t1, 'DELETE', '/jiedan/customer/' + custA)))
ok('cust_a 仍然存在', sql("select count(*) from sys_user where user_name='cust_a' and del_flag='0'") === '1')
ok('staff1 不能改客户归属', denied(await call(t1, 'PUT', '/jiedan/customer/owner', { userId: Number(custA), ownerId: s1 })))

section('G 下属管理员不能做总管理员的事')
ok('staff1 不能创建成员', denied(await call(t1, 'POST', '/jiedan/staff', { userName: 'x1', nickName: 'x', password: '123456' })))
ok('staff1 不能重置成员密码', denied(await call(t1, 'PUT', '/jiedan/staff/reset', { userId: s2, password: '123456' })))
ok('staff1 不能停用成员', denied(await call(t1, 'PUT', '/jiedan/staff/status', { userId: s2, status: '1' })))
ok('staff1 不能删除成员', denied(await call(t1, 'DELETE', '/jiedan/staff/' + s2)))
list = await call(t1, 'GET', '/jiedan/staff/list')
ok('staff1 的成员列表只有自己', good(list) && list.data.length === 1 && list.data[0].id === s1)
ok('staff1 不能手动触发全员提醒', denied(await call(t1, 'POST', '/jiedan/push/reminder')))
let pc = await call(t1, 'GET', '/jiedan/push/config')
ok('staff1 只看到自己的 SendKey', good(pc) && pc.data['push.sendkey.' + s1] === 'KEY_STAFF' && !('push.sendkey.' + bossId in pc.data), JSON.stringify(pc.data))
ok('staff1 不能改总管理员的 SendKey', denied(await call(t1, 'POST', '/jiedan/push/config', { ['push.sendkey.' + bossId]: 'HACK' })))
ok('staff1 不能改全局推送开关', denied(await call(t1, 'POST', '/jiedan/push/config', { 'push.enabled': 'true' })))
ok('staff1 可改自己的 SendKey', good(await call(t1, 'POST', '/jiedan/push/config', { ['push.sendkey.' + s1]: 'KEY_STAFF2' })))
ok('总管理员 SendKey 未被改动', sql(`select setting_value from jiedan_setting where setting_key='push.sendkey.${bossId}'`) === 'KEY_BOSS')
// 管理接口以外的若依系统接口
const sysUsers = await call(t1, 'GET', '/system/user/list')
ok('staff1 不能访问若依用户管理', denied(sysUsers))

section('H 总管理员的特权')
ol = await call(boss, 'GET', '/jiedan/order/list')
ok('boss 看到全部(含 staff1 新单)', good(ol) && ol.data.length >= 6, String(ol.data?.length))
ok('boss 读 staff1 的订单', good(await call(boss, 'GET', '/jiedan/order/2')))
r = await call(boss, 'PUT', '/jiedan/order', { id: 2, ownerId: s2 })
ok('boss 把订单 2 转派给 staff2', good(r) && r.data.ownerId === s2, JSON.stringify(r))
ok('转派后 staff1 看不到订单 2', denied(await call(t1, 'GET', '/jiedan/order/2')))
const t2 = await login('staff2', 'staff123')
ok('staff2 登录', !!t2)
ok('转派后 staff2 能看订单 2', good(await call(t2, 'GET', '/jiedan/order/2')))
ol = await call(t2, 'GET', '/jiedan/order/list')
ok('staff2 只看到订单 2', good(ol) && ol.data.length === 1 && ol.data[0].id === 2, JSON.stringify(ol.data?.map(o => o.id)))
ok('staff2 看不到 staff1 的订单 3', denied(await call(t2, 'GET', '/jiedan/order/3')))
r = await call(boss, 'PUT', '/jiedan/order', { id: 2, ownerId: bossId })
ok('boss 可把订单转回自己', good(r) && r.data.ownerId === bossId)
r = await call(boss, 'PUT', '/jiedan/order', { id: 2, ownerId: 999999 })
ok('boss 不能转给不存在的账号', denied(r))
cl1.data = (await call(boss, 'GET', '/jiedan/customer/list')).data
ok('boss 看到全部 3 个客户', cl1.data.length === 3, JSON.stringify(cl1.data.map(c => c.userName)))
ok('客户列表带归属管理员名', cl1.data.find(c => c.userName === 'cust_s1')?.ownerName === '费鹏飞' && cl1.data.find(c => c.userName === 'cust_a')?.ownerName === '尚琦凯', JSON.stringify(cl1.data.map(c => [c.userName, c.ownerName])))
ok('boss 把 cust_b 转交 staff2', good(await call(boss, 'PUT', '/jiedan/customer/owner', { userId: Number(sql("select user_id from sys_user where user_name='cust_b'")), ownerId: s2 })))

section('I 客户提交需求，自动落到归属管理员')
const cA = await login('cust_a', 'customer123')
const cS1 = await login('cust_s1', 'cust123')
const cB = await login('cust_b', 'customer123')
ok('三个客户登录', !!cA && !!cS1 && !!cB)
r = await call(cA, 'POST', '/jiedan/order/intake', { title: 'A的新需求', requirement: 'r', contact: 'c', ownerId: 2, byUserName: '甲客户' })
ok('cust_a 提交 -> 负责人=总管理员(忽略前端 ownerId=2)', good(r) && r.data.ownerId === bossId, JSON.stringify(r.data?.ownerId))
const idA = r.data?.id
r = await call(cS1, 'POST', '/jiedan/order/intake', { title: 'S1客户的新需求', requirement: 'r', contact: 'c', ownerId: 2 })
ok('cust_s1 提交 -> 负责人=staff1', good(r) && r.data.ownerId === s1, JSON.stringify(r.data?.ownerId))
const idS = r.data?.id
r = await call(cB, 'POST', '/jiedan/order/intake', { title: 'B的新需求', requirement: 'r', contact: 'c' })
ok('cust_b(已转交) 提交 -> 负责人=staff2', good(r) && r.data.ownerId === s2, JSON.stringify(r.data?.ownerId))
const idB = r.data?.id
ok('未读只通知相关人: 订单 S 的 unread=[staff1,boss]', JSON.stringify(sql(`select unread from jiedan_order where id=${idS}`).split(',').map(Number).sort((a, b) => a - b)) === JSON.stringify([bossId, s1].sort((a, b) => a - b)), sql(`select unread from jiedan_order where id=${idS}`))
ok('staff1 看得到 S 订单, staff2 看不到', good(await call(t1, 'GET', '/jiedan/order/' + idS)) && denied(await call(t2, 'GET', '/jiedan/order/' + idS)))
ok('staff2 看不到 A 订单(总管理员的)', denied(await call(t2, 'GET', '/jiedan/order/' + idA)))
ok('staff1 看不到 B 订单(staff2 的)', denied(await call(t1, 'GET', '/jiedan/order/' + idB)))
r = await call(cS1, 'POST', '/jiedan/portal/message', { orderId: idS, content: '你好' })
ok('客户留言成功', good(r), JSON.stringify(r))
ok('留言后未读=[staff1,boss] 不含 staff2', !sql(`select unread from jiedan_order where id=${idS}`).split(',').includes(String(s2)))
ok('客户不能访问管理接口 list', denied(await call(cS1, 'GET', '/jiedan/order/list')))
ok('客户不能访问成员接口', denied(await call(cS1, 'GET', '/jiedan/staff/list')))
ok('客户看不到别人的项目(portal)', denied(await call(cS1, 'GET', '/jiedan/portal/orders/' + idA)))
const mine = await call(cS1, 'GET', '/jiedan/portal/orders')
ok('客户只看到自己的项目', good(mine) && mine.data.every(o => o.customerAccount === 'cust_s1') && mine.data.length >= 1)
ok('staff1 不能走客户 intake 接口', denied(await call(t1, 'POST', '/jiedan/order/intake', { title: 'x' })))

section('J 停用 / 重置密码 立即踢下线')
ok('停用前 staff2 可用', good(await call(t2, 'GET', '/jiedan/order/list')))
ok('boss 停用 staff2', good(await call(boss, 'PUT', '/jiedan/staff/status', { userId: s2, status: '1' })))
ok('停用后 staff2 旧令牌失效', denied(await call(t2, 'GET', '/jiedan/order/list')))
ok('停用后 staff2 无法重新登录', (await login('staff2', 'staff123')) === null)
ok('boss 启用 staff2', good(await call(boss, 'PUT', '/jiedan/staff/status', { userId: s2, status: '0' })))
const t2b = await login('staff2', 'staff123')
ok('启用后 staff2 可登录', !!t2b)
ok('boss 重置 staff2 密码', good(await call(boss, 'PUT', '/jiedan/staff/reset', { userId: s2, password: 'newpw123' })))
ok('重置后旧令牌失效', denied(await call(t2b, 'GET', '/jiedan/order/list')))
ok('新密码可登录', !!(await login('staff2', 'newpw123')))
ok('boss 不能通过成员接口重置自己/总管理员', denied(await call(boss, 'PUT', '/jiedan/staff/reset', { userId: bossId, password: 'hack123' })))
ok('boss 不能停用总管理员', denied(await call(boss, 'PUT', '/jiedan/staff/status', { userId: bossId, status: '1' })))
ok('boss 不能删除总管理员', denied(await call(boss, 'DELETE', '/jiedan/staff/' + bossId)))
ok('boss 不能用成员接口重置客户密码', denied(await call(boss, 'PUT', '/jiedan/staff/reset', { userId: Number(custA), password: 'hack123' })))
ok('名下有项目的成员不能删除', denied(await call(boss, 'DELETE', '/jiedan/staff/' + s1)))

section('K 空负责人保护: 无令牌')
ok('无令牌访问订单列表被拒', denied(await call(null, 'GET', '/jiedan/order/list')))

finish()
