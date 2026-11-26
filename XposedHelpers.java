package de.robv.android.xposed;

public final class XposedHelpers {
    private XposedHelpers() {}
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader cl,
                                                          String methodName, Object... parameterTypesAndCallback) throws Throwable {
        return null;
    }
    public static Object newInstance(Class<?> clazz, Object... args) throws Throwable { return null; }
    public static void setObjectField(Object obj, String fieldName, Object value) {}
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) throws Throwable { return null; }
}
