package io.github.dsh.mockroute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON parser used to avoid any third-party runtime library. */
final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON 为空");
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("JSON 末尾有多余内容");
        return v;
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw err("JSON 意外结束");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == 't' && s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (c == 'f' && s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (c == 'n' && s.startsWith("null", i)) { i += 4; return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw err("无法识别的 JSON 字符 '" + c + "'");
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            if (!peek('"')) throw err("对象键必须是字符串");
            String k = string();
            ws();
            if (!peek(':')) throw err("对象键后缺少 ':'");
            i++;
            m.put(k, value());
            ws();
            if (peek(',')) { i++; continue; }
            if (peek('}')) { i++; return m; }
            throw err("对象缺少 ',' 或 '}'");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++; ws();
        if (peek(']')) { i++; return a; }
        while (true) {
            a.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            if (peek(']')) { i++; return a; }
            throw err("数组缺少 ',' 或 ']'");
        }
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (i >= s.length()) throw err("字符串转义不完整");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("Unicode 转义不完整");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw err("非法转义 \\" + e);
                }
            } else {
                if (c < 0x20) throw err("字符串含非法控制字符");
                b.append(c);
            }
        }
        throw err("字符串未闭合");
    }

    private Double number() {
        int start = i;
        if (peek('-')) i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (peek('.')) {
            i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        if (peek('e') || peek('E')) {
            i++;
            if (peek('+') || peek('-')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        String n = s.substring(start, i);
        try { return Double.valueOf(n); }
        catch (NumberFormatException e) { throw err("非法数字 " + n); }
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON 解析失败（位置 " + i + "）：" + msg);
    }
}
