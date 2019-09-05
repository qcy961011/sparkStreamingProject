package com.hainiu.spark.utils;

import cn.wanghaomiao.xpath.model.JXDocument;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public class JavaUtil {

    /**
     * 根据正反规则获取dom的正文
     *
     * @param doc                   Document
     * @param positiveXpathList     正规则列表
     * @param negativeXpathList     反规则列表
     * @return                      正文内容
     */
    public static String getcontext(Document doc, List<String> positiveXpathList, List<String> negativeXpathList) {
        JXDocument jx = new JXDocument(doc);

        List<Elements> positiveElementsList = getElementsList(jx, positiveXpathList);
        List<Elements> negativeElementsList = getElementsList(jx, negativeXpathList);

        // 将所有的反规则的元素节点从dom中移除
        for (Elements elements : negativeElementsList) {
            for (Element element : elements) {
                element.remove();
            }
        }

        // 获取正规则节点的内容
        String result = "";
        for (Elements elements : positiveElementsList) {
            String text = elements.text();
            if (text != null)
                result += text;
        }
        return result;
    }


    /**
     * 获取xpath在dom中对应的元素节点列表
     *
     * @param jx        JXDocument
     * @param xpathList xpath 列表
     * @return          Elements 列表
     */
    private static List<Elements> getElementsList(JXDocument jx, List<String> xpathList) {
        List<Elements> list = new ArrayList<Elements>();
        if (xpathList == null) {
            return list;
        }

        try {
            for (String xpath : xpathList) {
                List sel = jx.sel(xpath);
                Elements eles = new Elements(sel);
                list.add(eles);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }


    private static Pattern IP_PATTERN = Pattern
            .compile("(\\d{1,3}\\.){3}(\\d{1,3})");

    private final static Set<String> PublicSuffixSet = new HashSet<String>(
            Arrays.asList("com|org|net|gov|edu|co|tv|mobi|info|asia|xxx|onion|cn|com.cn|edu.cn|gov.cn|net.cn|org.cn|jp|kr|tw|com.hk|hk|com.hk|org.hk|se|com.se|org.se"
                    .split("\\|")));
    /**
     * 获取url的顶级域名
     */
    public static String getDomainName(URL url) {
        String host = url.getHost();
        if (host.endsWith("."))
            host = host.substring(0, host.length() - 1);
        if (IP_PATTERN.matcher(host).matches())
            return host;

        int index = 0;
        String candidate = host;
        for (; index >= 0; ) {
            index = candidate.indexOf('.');
            String subCandidate = candidate.substring(index + 1);
            if (PublicSuffixSet.contains(subCandidate)) {
                return candidate;
            }
            candidate = subCandidate;
        }
        return candidate;
    }

    /**
     * 判断对象是否为空, 可以对字符串, map, 数组进行判断
     *
     * @param obj 需要判断的对象
     * @return true: 为空, false: 不为空
     */
    public static boolean isEmpty(Object obj) {
        if (obj == null) {
            return true;
        } else if (obj instanceof String) {
            return "".equals(String.valueOf(obj).trim());
        } else if (obj instanceof Map<?, ?>) {
            return ((Map<?, ?>) obj).isEmpty();
        } else if (obj instanceof Collection<?>) {
            return ((Collection<?>) obj).isEmpty();
        } else if (obj.getClass().isArray()) {
            return Array.getLength(obj) == 0;
        }
        return false;
    }

    /**
     * 判断对象是否为空, 可以对字符串, map, 数组进行判断
     *
     * @param obj 需要判断的对象
     * @return true: 不为空, false: 为空
     */
    public static boolean isNotEnpty(Object obj) {
        return !isEmpty(obj);
    }

}
