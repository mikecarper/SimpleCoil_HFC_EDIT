package com.simplecoil.simplecoil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

final class TcpInputTestData {
    private TcpInputTestData() { }

    static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    static String nested(int depth, boolean arrays) {
        StringBuilder json = new StringBuilder("{\"unused\":");
        for (int i = 0; i < depth; i++) json.append(arrays ? "[" : "{\"x\":");
        json.append('0');
        for (int i = 0; i < depth; i++) json.append(arrays ? ']' : '}');
        return json.append('}').toString();
    }

    static void assertFitsFrame(String json) throws IOException {
        new DataOutputStream(new ByteArrayOutputStream()).writeUTF(
                TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + json);
    }

    static void invokeParser(Method parser, Object target, Object... arguments) throws Exception {
        try {
            parser.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            // The thousands of stack frames in this failure overflow Android 5.1's
            // instrumentation result transaction. Report the regression compactly.
            if (failure.getCause() instanceof StackOverflowError)
                throw new AssertionError("Network JSON caused StackOverflowError");
            throw failure;
        }
    }
}
