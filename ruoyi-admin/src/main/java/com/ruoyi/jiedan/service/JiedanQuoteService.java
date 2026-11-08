package com.ruoyi.jiedan.service;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.jiedan.mapper.JiedanQuoteMapper;
import com.ruoyi.jiedan.mapper.JiedanAccountMapper;

/** Pre-sales quotes never enter order/receipt totals until explicitly converted. */
@Service
public class JiedanQuoteService {
    private static final List<String> QUOTE_SOURCES = Arrays.asList("小慧","谢谢","硕硕","杨","小松","圈圈");
    private static final long MAX_SOURCE_NUMBER = 999999999L;
    @Autowired private JiedanQuoteMapper mapper;
    @Autowired private JiedanAccountMapper accounts;
    @Autowired private JiedanAccess access;
    @Autowired private IJiedanOrderService orders;
    @Autowired private JiedanAuditService audit;
    @Autowired private JiedanPushService push;

    public List<Map<String,Object>> list() {
        List<Map<String,Object>> rows=mapper.list(access.ownerScope(),access.currentUserId());
        rows.forEach(this::decodeQuote);
        return rows;
    }
    public Map<String,Object> detail(Long id) {return decodeQuote(visible(id,false));}

    @Transactional
    public Map<String,Object> create(Map<String,Object> input) {
        access.requireBoss();
        Object requestedEmployee = input.get("employeeId");
        if (requestedEmployee == null) requestedEmployee = accounts.selectDefaultQuoteEmployeeId();
        if (requestedEmployee == null) throw new ServiceException("请选择有效的报价人");
        Long employee = employee(requestedEmployee);
        Map<String,Object> row=new LinkedHashMap<>();
        applyRequirements(row, input);
        assignNumber(row,input);
        row.put("employeeId",employee); row.put("creatorId",access.currentUserId());
        mapper.insert(row);
        Long quoteId = id(row.get("id"));
        if (((String)row.get("title")).isEmpty()) mapper.setInitialTitle(quoteId, quoteNumber(row));
        Map<String,Object> saved = detail(quoteId);
        audit.record("quote.create","quote",quoteId,(String)saved.get("title"),"发送报价邀请");
        notifyInvitation(saved, "新需求待报价");
        return saved;
    }

    public List<Map<String,Object>> numbering() {
        access.requireBoss();
        Map<String,Long> counters = new HashMap<>();
        for (Map<String,Object> counter : mapper.counters())
            counters.put((String)counter.get("sourceName"), ((Number)counter.get("lastValue")).longValue());
        List<Map<String,Object>> result = new ArrayList<>();
        for (String source : QUOTE_SOURCES)
            result.add(Map.of("name",source,"next",counters.getOrDefault(source,0L)+1));
        return result;
    }

    private void assignNumber(Map<String,Object> row, Map<String,Object> input) {
        String source = text(input.get("sourceName"),20,"项目来源");
        String manual = text(input.get("manualNumber"),32,"已有单号").replace(" ","");
        if (row.get("sourceName") != null) {
            if ((!source.isEmpty() && !source.equals(row.get("sourceName"))) || !manual.isEmpty())
                throw new ServiceException("已分配的报价单号不能重复分配");
            return;
        }
        if (source.isEmpty() && manual.isEmpty()) return;
        if (!QUOTE_SOURCES.contains(source)) throw new ServiceException("请选择项目来源");
        if (manual.startsWith(source)) manual=manual.substring(source.length());
        boolean automatic = text(input.get("manualNumber"),32,"已有单号").isEmpty();
        if (!automatic && !manual.matches("[1-9][0-9]{0,8}"))
            throw new ServiceException("请填写所选姓名加正整数，例如"+source+"3");

        // The counter row is locked until the quote and its number both commit.
        mapper.ensureCounter(source);
        long last = mapper.lockCounter(source);
        long number = automatic ? last+1 : Long.parseLong(manual);
        if (number>MAX_SOURCE_NUMBER) throw new ServiceException("该来源的报价编号已达上限");
        if (!automatic && mapper.numberExists(source,number)) throw new ServiceException("该报价单号已使用");
        mapper.advanceCounter(source,number);
        row.put("sourceName",source); row.put("sourceSeq",number);
    }

    private String quoteNumber(Map<String,Object> row) {
        if (row.get("sourceName") != null) return row.get("sourceName").toString() + row.get("sourceSeq");
        return String.format(Locale.ROOT,"BJ-%06d",id(row.get("id")));
    }

    private String notificationLabel(Map<String,Object> row) {
        String number = quoteNumber(row);
        String title = (String)row.get("title");
        return number.equals(title) ? number : number + " " + title;
    }

    private void notifyInvitation(Map<String,Object> row, String heading) {
        Long assignee = id(row.get("employeeId"));
        String title = notificationLabel(row);
        NoticeTarget target = NoticeTarget.quote(id(row.get("id")));
        push.pushToTarget(assignee, heading, title + "，请查看需求并填写报价。", target);
        push.pushQuoteOwner(id(row.get("creatorId")), assignee, "报价邀请已发出",
                title + "，已邀请" + row.get("employeeName") + "报价。", target);
    }

    private void applyRequirements(Map<String,Object> row, Map<String,Object> input) {
        String title=text(input.get("title"),200,"报价标题");
        if (row.containsKey("id") && title.isEmpty()) throw new ServiceException("请填写报价标题");
        String requirement=text(input.get("requirement"),10000,"需求说明");
        List<Map<String,Object>> attachments=attachments(input.get("attachments"));
        if(requirement.isEmpty() && attachments.isEmpty()) throw new ServiceException("请填写需求说明或上传图片、文件");
        row.put("title",title); row.put("requirement",requirement); row.put("attachments",JSON.toJSONString(attachments));
    }

    @Transactional
    public Map<String,Object> edit(Long quoteId, Map<String,Object> input) {
        access.requireBoss();
        Map<String,Object> row = visible(quoteId, true);
        checkVersion(row, input);
        boolean invite = Boolean.TRUE.equals(input.get("requestPrice"));
        if (invite && "converted".equals(row.get("status"))) throw new ServiceException("已转为项目的报价单无法再次邀请报价");
        Long previous = id(row.get("employeeId"));
        Long requested = input.containsKey("employeeId") ? id(input.get("employeeId")) : previous;
        boolean reassigned = !requested.equals(previous);
        if (reassigned && !invite) throw new ServiceException("更换报价人时请保存并邀请报价");
        String previousNumber = quoteNumber(row);
        applyRequirements(row, input);
        assignNumber(row,input);
        if (previousNumber.equals(row.get("title"))) row.put("title",quoteNumber(row));
        if (invite) {
            row.put("newInvitation", true);
            row.put("employeeId", employee(requested));
            if (reassigned) {
                row.put("amount", null);
                row.put("quoteNote", null);
            }
            row.put("status", "pending");
            row.put("newNotice", true);
        }
        mapper.update(row);
        String title = (String)row.get("title");
        audit.record("quote.edit", "quote", quoteId, title, invite ? "更新需求并邀请报价" : "更新报价单");
        Map<String,Object> saved = detail(quoteId);
        if (invite) notifyInvitation(saved, "请按最新需求报价");
        return saved;
    }

    @Transactional
    public void markRead(List<Map<String,Long>> items) {
        if (items.isEmpty()) return;
        if (items.size() > 200) throw new ServiceException("一次最多标记 200 条报价");
        for (Map<String,Long> item : items) {
            if (item == null || item.get("id") == null || item.get("noticeVersion") == null
                    || item.get("id") < 1 || item.get("noticeVersion") < 1)
                throw new ServiceException("报价标识或通知版本无效");
        }
        mapper.markRead(access.currentUserId(), access.ownerScope(), items);
        List<Map<String,Long>> replies = new ArrayList<>();
        for (Map<String,Long> item : items)
            if (item.get("replyVersion") != null && item.get("replyVersion") > 0) replies.add(item);
        if (!replies.isEmpty()) mapper.markRepliesRead(access.currentUserId(), replies);
    }

    private boolean isCreator(Map<String,Object> row) {
        return access.currentUserId().equals(id(row.get("creatorId")));
    }

    public Map<String,Object> conversation(Long quoteId, Long before) {
        Map<String,Object> row = visible(quoteId,false);
        boolean creator = isCreator(row);
        if (!creator && !access.currentUserId().equals(id(row.get("employeeId"))))
            throw new ServiceException("仅报价双方可查看沟通记录");
        List<Map<String,Object>> rows = mapper.conversation(quoteId, creator ? null : access.currentUserId(), before);
        boolean more = rows.size() > 40;
        if (more) rows.remove(rows.size()-1);
        rows.forEach(this::decode);
        Collections.reverse(rows);
        Map<String,Object> page = new LinkedHashMap<>();
        page.put("items",rows); page.put("hasMore",more);
        return page;
    }

    private Map<String,Object> entry(Map<String,Object> quote, String kind, String content) {
        Map<String,Object> entry = new LinkedHashMap<>();
        entry.put("quoteId",quote.get("id")); entry.put("employeeId",quote.get("employeeId"));
        entry.put("authorId",access.currentUserId()); entry.put("authorName",access.currentName());
        entry.put("kind",kind); entry.put("content",content);
        return entry;
    }

    @Transactional
    public Map<String,Object> reply(Long quoteId, Map<String,Object> input) {
        Map<String,Object> row = visible(quoteId,true);
        Long actor = access.currentUserId(), assigned = id(row.get("employeeId"));
        boolean creator = isCreator(row);
        if (!creator && !actor.equals(assigned)) throw new ServiceException("仅报价双方可回复");
        String key = text(input.get("clientKey"),64,"消息标识");
        if (!key.matches("[a-zA-Z0-9_-]{12,64}")) throw new ServiceException("消息标识无效，请重新发送");
        Long existing = mapper.findClientKey(quoteId,actor,key);
        if (existing!=null) return Collections.singletonMap("id",existing);
        if ("converted".equals(row.get("status"))) throw new ServiceException("已转为项目，请在项目里继续沟通");
        if (!assigned.equals(id(input.get("employeeId")))) throw new ServiceException("报价人已变更，请重新打开报价单");
        String content = text(input.get("content"),5000,"回复");
        List<Map<String,Object>> files = attachments(input.get("attachments"));
        if (content.isEmpty() && files.isEmpty()) throw new ServiceException("请填写回复或添加附件");
        Long recipient = creator ? assigned : id(row.get("creatorId"));
        Map<String,Object> msg = entry(row,"reply",content);
        msg.put("recipientId",recipient); msg.put("clientKey",key); msg.put("attachments",JSON.toJSONString(files));
        mapper.insertMessage(msg); mapper.touchConversation(quoteId);
        String preview = access.currentName()+" 回复了「"+notificationLabel(row)+"」："+(content.isEmpty()?"发来了附件":content);
        NoticeTarget target = NoticeTarget.quoteChat(quoteId);
        if (creator) push.pushToTarget(recipient,"报价有新回复",preview,target);
        else push.pushQuoteOwner(recipient,null,"报价有新回复",preview,target);
        return Collections.singletonMap("id",msg.get("id"));
    }

    @Transactional
    public Map<String,Object> price(Long quoteId,Map<String,Object> input) {
        Map<String,Object> row=visible(quoteId,true);
        if(!id(row.get("employeeId")).equals(access.currentUserId())) throw new ServiceException("仅指定报价人可填写报价");
        checkVersion(row,input);
        if("converted".equals(row.get("status"))) throw new ServiceException("已转为项目，不能修改原报价");
        BigDecimal amount;
        try {amount=new BigDecimal(String.valueOf(input.get("amount")));}
        catch(Exception e) {throw new ServiceException("请填写有效报价金额");}
        if(amount.signum()<=0 || amount.compareTo(new BigDecimal("9999999999.99"))>0 || amount.stripTrailingZeros().scale()>2)
            throw new ServiceException("报价须大于 0，最多两位小数且不超过 9999999999.99");
        row.put("amount",amount); row.put("newNotice", true); row.put("quoteNote",text(input.get("quoteNote"),5000,"报价说明")); row.put("status","quoted");
        mapper.update(row);
        Map<String,Object> msg = entry(row,"price",(String)row.get("quoteNote"));
        msg.put("amount",amount); mapper.insertMessage(msg);
        audit.record("quote.price","quote",quoteId,(String)row.get("title"),"提交报价 ¥"+amount.toPlainString());
        push.pushQuoteOwner(id(row.get("creatorId")), null, "收到新报价",
                row.get("employeeName") + " 已为「" + notificationLabel(row) + "」报价 ¥" + amount.toPlainString(), NoticeTarget.quote(quoteId));
        return detail(quoteId);
    }

    @Transactional
    public Map<String,Object> assign(Long quoteId,Map<String,Object> input) {
        access.requireBoss();
        Map<String,Object> row=visible(quoteId,true); checkVersion(row,input);
        if("converted".equals(row.get("status"))) throw new ServiceException("已转成项目，请在项目中调整负责人");
        Long employee=employee(input.get("employeeId"));
        row.put("newInvitation", true);
        row.put("newNotice", true); row.put("employeeId",employee);row.put("amount",null);row.put("quoteNote",null);row.put("status","pending");mapper.update(row);
        audit.record("quote.assign","quote",quoteId,(String)row.get("title"),"重新指派报价，原报价已重置");
        Map<String,Object> saved = detail(quoteId);
        notifyInvitation(saved, "新需求待报价");
        return saved;
    }

    @Transactional
    public Map<String,Object> convert(Long quoteId,Map<String,Object> input) {
        access.requireBoss();
        Map<String,Object> row=visible(quoteId,true);
        // Lock + persisted order ID makes retries/concurrent conversion idempotent.
        if(row.get("orderId")!=null) return decodeQuote(row);
        checkVersion(row,input);
        if(!"quoted".equals(row.get("status"))) throw new ServiceException("请等待提交报价后再转为项目");
        employee(row.get("employeeId"));
        Map<String,Object> project=new LinkedHashMap<>();
        project.put("title",row.get("title"));project.put("requirement",row.get("requirement"));
        project.put("attachments",JSON.parseArray((String)row.get("attachments")));
        project.put("amount",row.get("amount"));project.put("ownerId",row.get("employeeId"));
        project.put("status","pending");project.put("channel","other");project.put("priority","medium");
        Map<String,Object> created=orders.create(project);
        row.put("orderId",created.get("id"));row.put("status","converted");mapper.update(row);
        String note=String.valueOf(row.getOrDefault("quoteNote",""));
        orders.flowEvent(id(created.get("id")),"由报价单 #"+quoteId+" 转入。提交报价说明："+note,null,null);
        audit.record("quote.convert","quote",quoteId,(String)row.get("title"),"转为项目 #"+created.get("id"));
        return detail(quoteId);
    }

    @Transactional
    public Map<String,Object> rename(Long quoteId, Map<String,Object> input) {
        access.requireBoss();
        Map<String,Object> current = visible(quoteId, true);
        checkVersion(current, input);
        String title = text(input.get("title"), 200, "报价标题");
        if (title.isEmpty()) throw new ServiceException("请填写报价标题");
        if (!title.equals(current.get("title"))) {
            mapper.rename(quoteId, title);
            audit.record("quote.rename", "quote", quoteId, title, "原名称：" + current.get("title"));
        }
        return detail(quoteId);
    }

    @Transactional
    public void delete(Long quoteId,Map<String,Object> input) {
        access.requireBoss();Map<String,Object> row=visible(quoteId,true);checkVersion(row,input);
        row.put("deleted",1);mapper.update(row);
        audit.record("quote.delete","quote",quoteId,(String)row.get("title"),"删除报价单；关联项目和已共享附件保持不变");
    }

    private Map<String,Object> visible(Long quoteId,boolean lock) {
        Map<String,Object> row=mapper.find(quoteId,lock,access.currentUserId());
        if(row==null || (!access.isBoss() && !access.currentUserId().equals(id(row.get("employeeId")))))
            throw new ServiceException("报价单不存在或无权访问");
        return row;
    }
    private void checkVersion(Map<String,Object> row,Map<String,Object> input) {
        if(!id(row.get("version")).equals(id(input.get("version")))) throw new ServiceException("报价单已更新，请刷新后重试");
    }
    private Long employee(Object value) {
        Long uid=id(value);
        if(!access.isAssignable(uid) || !accounts.selectRoleKeys(uid).contains(JiedanAccess.ROLE_STAFF) || accounts.selectRoleKeys(uid).contains(JiedanAccess.ROLE_BOSS))
            throw new ServiceException("请选择有效且未停用的报价人");
        return uid;
    }
    private Map<String,Object> decodeQuote(Map<String,Object> row) {
        row.put("quoteNo", quoteNumber(row));
        return decode(row);
    }
    private Map<String,Object> decode(Map<String,Object> row) {
        Object value=row.get("attachments");
        if(value instanceof String)row.put("attachments",JSON.parseArray((String)value));
        return row;
    }
    private Long id(Object v) {try{return Long.valueOf(String.valueOf(v));}catch(Exception e){throw new ServiceException("缺少或无效的记录标识/版本");}}
    private String text(Object v,int max,String field) {
        if(v!=null && !(v instanceof String))throw new ServiceException(field+"格式不正确");
        String s=v==null?"":((String)v).trim();if(s.length()>max)throw new ServiceException(field+"不能超过 "+max+" 字");return s;
    }
    private List<Map<String,Object>> attachments(Object input) {
        List<Map<String,Object>> result=new ArrayList<>();
        if(input==null)return result;
        // 附件张数不限（单个文件 50MB 上限由上传接口控制）
        if(!(input instanceof List))throw new ServiceException("附件格式不正确");
        for(Object v:(List<?>)input) {
            if(!(v instanceof Map))throw new ServiceException("附件格式不正确");
            Map<?,?> a=(Map<?,?>)v;
            String url=text(a.get("url"),250,"附件地址");
            if(!url.matches("/jiedan/media/[0-9a-f]{32}\\.[a-z0-9]{1,10}"))throw new ServiceException("请使用已上传的附件");
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("url",url);item.put("name",text(a.get("name"),255,"附件名称"));
            String type=text(a.get("type"),20,"附件类型");
            if(!Arrays.asList("image","video","audio","file").contains(type))type="file";
            item.put("type",type);item.put("ext",text(a.get("ext"),10,"扩展名"));
            long size=id(a.get("size"));if(size<0||size>50L*1024*1024)throw new ServiceException("附件大小无效");
            item.put("size",size);result.add(item);
        }
        return result;
    }
}
