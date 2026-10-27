package com.jtzj.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.jtzj.agent.core.config.AgentProperties;

/**
 * 关键字 → 技能名的<b>服务端路由</b>：证件类任务不靠模型自觉，也不靠调用方点名。
 *
 * <p>动机：四类证件的结论只来自技能通道（镜像内置 {@code ips} CLI），一旦模型先调了
 * {@code extract_document_fields}，通用 OCR 会把证照版面读成一段文本，字段值看着像真的、
 * 其实没有专用接口的置信度与「未检出」终局。生产上 resource-admin 写 {@code agent_task} 时
 * {@code skill_names} 全是 NULL，技能正文根本不进上下文，于是四类全走通用通道。
 *
 * <p>本类把「命中哪些类型」在服务端算出来（关键字在指令里，命中几类就是几类，互不排斥），
 * 调用方传的 {@code skills} 与之取并集，正文由 {@link SkillRepositoryFactory#inlineFor} 直接
 * 注入任务提示词。注入的意义是<b>省掉 {@code load_skill_through_path} 那一趟往返</b>：
 * 正文本来最终要落进上下文，让模型自己去 load 等于多付一次往返、每轮重发整段上下文，
 * 命中类型越多越亏（四类全中时现状要 9 次调用，注入后 5 次）。
 *
 * <p>关键字表是这里唯一的事实来源，但它和两处文案绑在一起：sys-prompt 里的关键字路由句
 * （{@code application.yml} 的 {@code agent.runner.sys-prompt}）与技能目录名。改名要三处同步，
 * 见 {@code docs/DESIGN.md} §9。
 */
@Component
public class SkillRouter {

    private static final Logger log = LoggerFactory.getLogger(SkillRouter.class);

    /**
     * 一条关键字到技能的映射。顺序即注入顺序：固定顺序让「同一组命中」的任务提示词逐字节相同，
     * 上游前缀缓存按命中组合成家族，家族基数就是常用类型数。
     */
    private record Entry(String skill, String subcommand, List<String> keywords) {
    }

    /**
     * 关键字一律小写；命中判定对指令做小写化，中文不受影响。
     *
     * <p>「发票专用章 / 发票章 / 财务专用章」里的「发票」不算发票任务，所以 invoice 的命中判定
     * 先把这些词从扫描文本里抹掉（印章任务几乎必然出现「发票专用章」，不处理就会多注入一份技能正文）。
     */
    private static final List<Entry> TABLE = List.of(
            new Entry("idcard-recognition", "idcard",
                    List.of("身份证", "居民身份证", "公民身份号码", "二代证", "二代身份证", "idcard")),
            new Entry("seal-recognition", "seal",
                    List.of("印章", "公章", "盖章", "加盖", "红章", "灰章", "骑缝章", "印章章样", "seal")),
            new Entry("invoice-recognition", "invoice",
                    List.of("发票", "增值税发票", "invoice")),
            new Entry("bizlic-recognition", "bizlic",
                    List.of("营业执照", "工商执照", "社会统一信用代码", "统一社会信用代码", "business license"))
    );

    /** 这些技能对应的 IPS 接口只收图片：指令里只有 PDF 来源时，技能正文自己让模型走通用抽取。 */
    private static final Set<String> IMAGE_ONLY = Set.of("idcard-recognition", "bizlic-recognition");

    private final AgentProperties props;
    private final SkillRepositoryFactory skills;

    public SkillRouter(AgentProperties props, SkillRepositoryFactory skills) {
        this.props = props;
        this.skills = skills;
    }

    /**
     * 指令命中的技能名，按 {@link #TABLE} 顺序、只保留真正安装了的（运维改名或撤下技能后
     * 自动不注入，不报错）。
     *
     * @return 命中且已安装的技能名，最多 {@code agent.skills.max-requested} 个
     */
    public List<String> detect(String instruction) {
        if (instruction == null || instruction.isBlank() || !skills.enabled()) {
            return List.of();
        }
        String lower = maskQualifiedFieldNames(instruction.toLowerCase(Locale.ROOT));
        // invoice 用抹掉专用章的文本判定，其余用原文。
        String forInvoice = lower.replaceAll("(发票|财务|合同|银行)专用章", "").replaceAll("发票章", "");
        Set<String> available = new LinkedHashSet<>(skills.availableNames());
        List<String> hits = new ArrayList<>(TABLE.size());
        for (Entry entry : TABLE) {
            if (!available.contains(entry.skill())) {
                continue;
            }
            String haystack = "invoice-recognition".equals(entry.skill()) ? forInvoice : lower;
            for (String keyword : entry.keywords()) {
                if (haystack.contains(keyword)) {
                    hits.add(entry.skill());
                    break;
                }
            }
        }
        int cap = Math.max(1, props.getSkills().getMaxRequested());
        if (hits.size() > cap) {
            log.warn("instruction hits {} skills, injecting the first {} (agent.skills.max-requested)",
                    hits.size(), cap);
            return hits.subList(0, cap);
        }
        return hits;
    }

    /**
     * 「<b>受委托人</b>身份证号码」这类<b>限定式字段名</b>不算该证件的任务。
     *
     * <p>KIE 任务把要抽的字段直接写在指令末尾的冒号后面，字段名里的证件词是<b>文档里某个人的属性</b>，
     * 不是卡面：授权委托书、登记表、协议里都写着「受委托人身份证号码」，来源就是那一页纸，
     * {@code ips idcard} 对着它只会「未检出」，白烧一趟往返。生产 2026-10-27 实测：通用抽取先出了
     * 正确的字段值，后面那条 {@code ips idcard}（缓存命中 90ms）纯属浪费。
     *
     * <p>判定口径：取指令<b>最后一个冒号</b>之后的字段清单，逐个字段名看证件关键字是否出现在
     * <b>开头</b>——在开头就是卡面自己的字段（{@code 身份证号码}、{@code 印章名称}、{@code 发票号码}），
     * 前面还挂着限定词就是「别人的身份证号」（{@code 受委托人身份证号码}），整名从扫描文本里抹掉。
     * 关键字只要还在正文里或别的字段名开头出现，就照旧命中，所以「一项限定式字段名」不会让真证件任务
     * 掉技能；只有「整条指令里这个类型仅以限定式字段名出现」才不路由——那正是通用抽取该干的活。
     * 指令里没有字段清单冒号（或冒号后紧跟 {@code //}，那是 URL 的协议头）时不掩码，退回原行为。
     */
    private static String maskQualifiedFieldNames(String text) {
        String fields = fieldListAfterLastColon(text);
        if (fields.isEmpty()) {
            return text;
        }
        String masked = text;
        for (String field : fields.split("[,，、;；。\s]+")) {
            for (Entry entry : TABLE) {
                for (String keyword : entry.keywords()) {
                    if (field.indexOf(keyword) > 0) {
                        masked = masked.replace(field, "□".repeat(field.length()));
                        break;
                    }
                }
            }
        }
        return masked;
    }

    /** 字段清单 = 最后一个冒号之后的内容；跳过 {@code http://} 这类协议头里的冒号。 */
    private static String fieldListAfterLastColon(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char colon = text.charAt(i);
            if (colon != ':' && colon != '：') {
                continue;
            }
            String rest = text.substring(i + 1);
            if (!rest.startsWith("//")) {
                return rest;
            }
        }
        return "";
    }

    /**
     * 调用方点名的技能优先，其后追加关键字命中的，去重。点名的一律保留（提交时已校验过形状），
     * 命中的只留已安装的。
     *
     * @return 注入顺序：点名在前，命中在后；两者都空时返回空集合（技能块保持全量元数据）
     */
    public List<String> merge(List<String> requested, List<String> detected) {
        Set<String> out = new LinkedHashSet<>();
        if (requested != null) {
            out.addAll(requested);
        }
        if (detected != null) {
            out.addAll(detected);
        }
        return new ArrayList<>(out);
    }

    /** 技能名 → {@code ips} 子命令；表里没有的技能（运维自加）返回 null，不参与完整性判定。 */
    public static String subcommandOf(String skill) {
        for (Entry entry : TABLE) {
            if (entry.skill().equals(skill)) {
                return entry.subcommand();
            }
        }
        return null;
    }

    /**
     * 这些技能在<b>只有 PDF 来源</b>时不该期望出现 {@code ips} 命令：技能正文里写明
     * 「PDF 不支持，改用 extract_document_fields」，此时模型走通用抽取是照正文办事，不是漏跑。
     */
    public static boolean imageOnly(String skill) {
        return IMAGE_ONLY.contains(skill);
    }

    /** 给强制重试那一轮拼提示：命中了哪几类、每类要跑哪条命令。 */
    public static String commandsFor(List<String> detected) {
        StringBuilder sb = new StringBuilder();
        for (String skill : detected) {
            String command = subcommandOf(skill);
            if (command != null) {
                sb.append("ips ").append(command).append(" src/<该类证件的文件>；");
            }
        }
        return sb.toString();
    }
}