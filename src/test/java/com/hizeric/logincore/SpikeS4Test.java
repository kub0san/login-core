package com.hizeric.logincore;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

public class SpikeS4Test {

    @Test
    void inspectAuthMeApi() {
        try {
            Class<?> clazz = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            System.out.println("AuthMeApi found: " + clazz.getName());
            for (Method m : clazz.getDeclaredMethods()) {
                System.out.println("  " + m.toString());
            }
        } catch (ClassNotFoundException e) {
            System.out.println("AuthMeApi class not found: " + e.getMessage());
        }
    }
}
