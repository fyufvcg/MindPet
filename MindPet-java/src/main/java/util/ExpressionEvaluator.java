package util;

public final class ExpressionEvaluator {

    private ExpressionEvaluator() {
    }

    public static double evaluate(String expression) {
        return new Parser(expression).parse();
    }

    private static final class Parser {
        private final String s;
        private int pos = -1;
        private int ch;

        private Parser(String s) {
            this.s = s.replaceAll("\\s+", "");
        }

        private double parse() {
            nextChar();
            double x = parseExpression();
            if (pos < s.length()) {
                throw new IllegalArgumentException("表达式格式错误");
            }
            return x;
        }

        private void nextChar() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        private double parseExpression() {
            double x = parseTerm();
            for (;;) {
                if (eat('+')) x += parseTerm();
                else if (eat('-')) x -= parseTerm();
                else return x;
            }
        }

        private double parseTerm() {
            double x = parseFactor();
            for (;;) {
                if (eat('*')) x *= parseFactor();
                else if (eat('/')) x /= parseFactor();
                else return x;
            }
        }

        private double parseFactor() {
            if (eat('+')) return parseFactor();
            if (eat('-')) return -parseFactor();

            double x;
            int startPos = this.pos;
            if (eat('(')) {
                x = parseExpression();
                if (!eat(')')) throw new IllegalArgumentException("括号不匹配");
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                x = Double.parseDouble(s.substring(startPos, this.pos));
            } else {
                throw new IllegalArgumentException("表达式格式错误");
            }

            if (eat('^')) x = Math.pow(x, parseFactor());
            return x;
        }
    }
}
