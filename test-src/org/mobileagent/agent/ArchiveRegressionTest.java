package org.mobileagent.agent;

import java.io.File;
import java.lang.reflect.*;
import java.util.List;
import org.json.JSONObject;

public final class ArchiveRegressionTest {
    static Class<?> model, storeClass;
    static int checks;
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target instanceof Class ? ((Class<?>) target).getDeclaredMethod(name, types)
                : target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target instanceof Class ? null : target, args);
    }
    static Object field(Object o, String key) throws Exception {
        Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o);
    }
    static void set(Object o, String key, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); f.set(o, value);
    }
    static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label); checks++;
    }
    static Object store(File dir) throws Exception {
        Class<?> faults = Class.forName("org.mobileagent.agent.DurableStore$FaultInjector");
        Constructor<?> c = storeClass.getDeclaredConstructor(File.class, faults);
        c.setAccessible(true); return c.newInstance(dir, null);
    }
    public static void main(String[] args) throws Exception {
        model = Class.forName("org.mobileagent.agent.Conversation");
        storeClass = Class.forName("org.mobileagent.agent.ConversationStore");
        JSONObject legacy = new JSONObject("{\"id\":\"archive_test\",\"version\":1,\"title\":\"kept\",\"messages\":[{\"role\":\"user\",\"content\":\"preserve\"}],\"custom\":\"keep\"}");
        Object conversation = call(model, "fromJson", new Class[]{JSONObject.class}, legacy);
        check(!((Boolean) field(conversation, "archived")), "legacy defaults to unarchived");
        set(conversation, "archived", true);
        JSONObject archived = (JSONObject) call(conversation, "toJson", new Class[0]);
        check(archived.getBoolean("archived"), "archive JSON flag");
        check(archived.getJSONArray("messages").toString().equals(legacy.getJSONArray("messages").toString()), "messages preserved");
        check("keep".equals(archived.getString("custom")), "unknown fields preserved");
        Object loaded = call(model, "fromJson", new Class[]{JSONObject.class}, archived);
        check((Boolean) field(loaded, "archived"), "archive round trip");
        JSONObject bad = new JSONObject(archived.toString()).put("archived", "true");
        boolean rejected = false;
        try { call(model, "fromJson", new Class[]{JSONObject.class}, bad); }
        catch (InvocationTargetException e) { rejected = e.getCause() instanceof IllegalArgumentException; }
        check(rejected, "invalid archive type rejected");
        File dir = new File(args[0]);
        if (dir.exists() || !dir.mkdirs()) throw new IllegalStateException("Use a fresh test directory");
        Object storage = store(dir);
        call(storage, "saveOrThrow", new Class[]{model}, loaded);
        Object disk = call(store(dir), "load", new Class[]{String.class}, "archive_test");
        check(disk != null && (Boolean) field(disk, "archived"), "archive survives store restart");
        Class<?> workspace = Class.forName("org.mobileagent.agent.Workspace");
        List<?> list = (List<?>) call(storage, "list", new Class[]{workspace}, new Object[]{null});
        check(list.size() == 1 && (Boolean) field(list.get(0), "archived"), "cached summary archived");
        list = (List<?>) call(store(dir), "list", new Class[]{workspace}, new Object[]{null});
        check(list.size() == 1 && (Boolean) field(list.get(0), "archived"), "index survives restart");
        set(disk, "archived", false);
        call(storage, "saveOrThrow", new Class[]{model}, disk);
        Object restored = call(store(dir), "load", new Class[]{String.class}, "archive_test");
        check(!((Boolean) field(restored, "archived")), "restore survives restart");
        list = (List<?>) call(store(dir), "list", new Class[]{workspace}, new Object[]{null});
        check(!(Boolean) field(list.get(0), "archived"), "restored index visible");
        System.out.println("ALL PASS: " + checks);
    }
}
