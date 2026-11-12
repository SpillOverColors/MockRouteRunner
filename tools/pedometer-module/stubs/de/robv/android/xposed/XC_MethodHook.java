package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        public Member method;
        public void setResult(Object result) {}
        public Object getResult() { return null; }
        public boolean hasThrowable() { return false; }
        public Throwable getThrowable() { return null; }
    }
    public static final class Unhook {}
}
