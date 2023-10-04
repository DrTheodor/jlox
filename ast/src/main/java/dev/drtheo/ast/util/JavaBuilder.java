package dev.drtheo.ast.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;

public class JavaBuilder extends PrintWriter {

    private int elevation = 0;
    private static final String spaces = "    ";

    public JavaBuilder(String path, Charset charset) throws IOException {
        super(path, charset);
    }

    public void elevate() {
        this.elevation++;
    }

    public void unelevate() {
        this.elevation--;
    }

    public void header(String line) {
        this.println(line);

        if (line.startsWith("package")) {
            super.println();
        }
    }

    public void clazz(String modifier, String name) {
        this.clazz(modifier, name, null);
    }

    public void clazz(String modifier, String name, String suffix) {
        this.element(modifier + " class", name, suffix);
    }

    public void interf4ce(String modifier, String name) {
        this.interf4ce(modifier, name, null);
    }

    public void interf4ce(String modifier, String name, String suffix) {
        this.element(modifier + " interface", name, suffix);
    }

    public void element(String modifier, String name, String suffix) {
        super.println();

        StringBuilder code = new StringBuilder().append(modifier).append(" ").append(name);

        if (suffix != null) {
            code.append(" ").append(suffix);
        }

        this.begin(code.toString());
    }

    public void begin(String code) {
        this.raw(code + " {");
        this.elevate();
    }

    public void end() {
        this.unelevate();
        this.raw("}");
    }

    public void newline() {
        super.println();
    }

    @Override
    public void println(String x) {
        super.println(x + ";");
    }

    public void raw(String line) {
        super.println(line);
    }

    @Override
    public void write(String s, int off, int len) {
        String tab = spaces.repeat(this.elevation);

        s = tab + s;
        len += tab.length();
        super.write(s, off, len);
    }
}
