package org.example.service.search;

import java.util.*;

/**
 * 应用层 BM25 打分器（Sparse 检索通路，混合检索的一半）。
 *
 * 为什么在应用层而不是用 Milvus 原生 BM25 Function：
 * 1) Milvus 2.6 的原生 BM25 需要 collection 增加 SparseFloatVector 字段 + Function 并重灌全量数据，
 *    当前演示规模（数百分片）重灌成本 > 收益；
 * 2) BM25 对精确技术关键词（错误码、异常类名、服务名）的召回优势在小规模下应用层即可复现；
 * 3) 生产规模方案已明确：切换 Milvus 原生 BM25 / Elasticsearch，接口不变（hybridSearch 对外签名不动）。
 *
 * 标准BM25公式（k1=1.2, b=0.75）：
 * score(D,Q) = Σ_t IDF(t) * (tf * (k1+1)) / (tf + k1 * (1 - b + b * |D|/avgdl))
 */
public class Bm25Scorer {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<String[]> docTerms;      // 每篇文档分词结果
    private final double avgDocLength;          // 平均文档长度（词数）
    private final Map<String, Double> idf;      // 词 -> IDF

    public Bm25Scorer(List<String> documents) {
        this.docTerms = new ArrayList<>(documents.size());
        for (String doc : documents) {
            docTerms.add(tokenize(doc));
        }
        long total = docTerms.stream().mapToLong(t -> t.length).sum();
        this.avgDocLength = docTerms.isEmpty() ? 1.0 : (double) total / docTerms.size();
        this.idf = buildIdf(docTerms);
    }

    /** 对 query 打分，返回 docIndex -> BM25 分数（降序排名用） */
    public Map<Integer, Double> score(String query) {
        String[] queryTerms = tokenize(query);
        Map<Integer, Double> scores = new HashMap<>();
        for (int i = 0; i < docTerms.size(); i++) {
            double s = 0.0;
            for (String term : queryTerms) {
                s += bm25Term(term, docTerms.get(i));
            }
            if (s > 0) {
                scores.put(i, s);
            }
        }
        return scores;
    }

    private double bm25Term(String term, String[] doc) {
        Double idfVal = idf.get(term);
        if (idfVal == null) {
            return 0.0;
        }
        int tf = 0;
        for (String t : doc) {
            if (t.equals(term)) {
                tf++;
            }
        }
        if (tf == 0) {
            return 0.0;
        }
        double dl = doc.length;
        double norm = K1 * (1 - B + B * dl / avgDocLength);
        return idfVal * (tf * (K1 + 1)) / (tf + norm);
    }

    private Map<String, Double> buildIdf(List<String[]> docs) {
        Map<String, Integer> docFreq = new HashMap<>();
        for (String[] doc : docs) {
            for (String unique : new HashSet<>(Arrays.asList(doc))) {
                docFreq.merge(unique, 1, Integer::sum);
            }
        }
        Map<String, Double> idfMap = new HashMap<>();
        int n = docs.size();
        for (Map.Entry<String, Integer> e : docFreq.entrySet()) {
            // 经典平滑 IDF，避免负值
            idfMap.put(e.getKey(), Math.log((n - e.getValue() + 0.5) / (e.getValue() + 0.5) + 1.0));
        }
        return idfMap;
    }

    /** 中英文混合分词：英文/数字按词、连续汉字按 2-gram（演示级，够用且无外部依赖） */
    static String[] tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder han = new StringBuilder();

        Runnable flushLatin = () -> {
            if (latin.length() > 0) {
                tokens.add(latin.toString().toLowerCase());
                latin.setLength(0);
            }
        };
        Runnable flushHan = () -> {
            if (han.length() > 0) {
                String s = han.toString();
                for (int i = 0; i < s.length(); i++) {
                    for (int n = 1; n <= 2 && i + n <= s.length(); n++) {
                        tokens.add(s.substring(i, i + n));
                    }
                }
                han.setLength(0);
            }
        };

        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                flushLatin.run();
                han.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushHan.run();
                latin.append(c);
            } else {
                flushLatin.run();
                flushHan.run();
            }
        }
        flushLatin.run();
        flushHan.run();
        return tokens.toArray(new String[0]);
    }
}
