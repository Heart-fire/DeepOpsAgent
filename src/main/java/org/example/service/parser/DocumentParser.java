package org.example.service.parser;

import java.nio.file.Path;

/**
 * 文档解析器策略接口：任意格式 → 统一 Markdown 中间格式。
 *
 * 为什么统一转 Markdown（而非 HTML/纯文本）：
 * - 下游分片（DocumentChunkService）按 '#' 标题切章节，MD 是携带层级语义的最薄载体；
 * - 比 HTML 少一层标签解析，比纯文本多一层结构（标题/表格）；
 * - 解析器新增格式（未来 Excel/PPT）只需产出同一种中间格式，分片与向量化零改动。
 *
 * 选型说明：
 * 白名单仅 md/txt/docx/pdf 三类四种，格式广度需求为零，故不用 Tika 门面
 * （其价值在上百种格式的统一入口，代价是默认输出拍平结构的纯文本）；
 * POI/PDFBox 原库直读可拿到标题样式与段落边界——结构深度正是分片所依赖的。
 * 若未来开放任意格式上传（工单附件等），再引入 Tika + XHTML handler。
 */
public interface DocumentParser {

    /** 本解析器支持的扩展名（小写，不含点），如 ["md", "txt"] */
    String[] supportedExtensions();

    /**
     * 解析文档为 Markdown 文本。
     *
     * @param file 本地文件路径
     * @return Markdown 中间格式文本（无 FrontMatter，标题用 #，表格用管道符）
     * @throws ScannedPdfException PDF 为扫描件（无文本层）时抛出，由上传入口转为明确提示
     * @throws IllegalArgumentException 文件损坏、加密或格式非法
     */
    String parse(Path file);
}
