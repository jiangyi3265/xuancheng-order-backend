import { createECDH, randomBytes, randomUUID } from 'node:crypto'
import { login, call, ok, good, denied, sql, finish } from './lib.mjs'
const token=await login('admin','admin123')
const api=(method,path,body)=>call(token,method,'/jiedan/webpush/'+path,body)
const config=await api('GET','key')
ok('系统推送返回有效 P256 公钥',good(config)&&Buffer.from(config.data?.key||'','base64url').length===65,config.msg)
ok('跨请求复用相同公钥',(await api('GET','key')).data?.key===config.data?.key)
const binding=randomUUID()
const device={binding,endpoint:'https://fcm.googleapis.com/fcm/send/'+randomUUID(),p256dh:createECDH('prime256v1').generateKeys().toString('base64url'),auth:randomBytes(16).toString('base64url')}
ok('保存本机订阅',good(await api('POST','device',device)))
ok('重复注册只保存一次',good(await api('POST','device',device))&&sql("select count(*) from jiedan_webpush_device where binding='"+binding+"'")==='1')
ok('非推送服务地址不能注册',denied(await api('POST','device',{...device,endpoint:'https://example.org/'})))
ok('取消后不再投递到设备',good(await api('DELETE','device/'+binding))&&denied(await api('POST','test/'+binding)))
finish()
