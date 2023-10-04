package dev.drtheo.ast.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Util {

    public static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int length; (length = stream.read(buffer)) != -1; ) {
            result.write(buffer, 0, length);
        }

        return result.toString(StandardCharsets.UTF_8);
    }

    public static String read(String path) throws IOException {
        return read(Util.class.getClassLoader().getResourceAsStream(path));
    }

    public static String capitalize(String text) {
        return text.substring(0, 1).toUpperCase() + text.substring(1);
    }
}
