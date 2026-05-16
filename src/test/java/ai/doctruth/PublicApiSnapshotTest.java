package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublicApiSnapshotTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/ai/doctruth/public-api-snapshot.txt");

    @Test
    @DisplayName("public SDK API surface changes are explicit")
    void publicApiSurfaceMatchesSnapshot() throws Exception {
        String actual = String.join("\n", publicApiLines()) + "\n";
        if (Boolean.getBoolean("doctruth.updatePublicApiSnapshot")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual);
        }
        assertThat(Files.readString(SNAPSHOT)).isEqualTo(actual);
    }

    private static List<String> publicApiLines() throws Exception {
        var lines = new ArrayList<String>();
        for (Class<?> type : publicApiTypes()) {
            addType(lines, type);
        }
        return lines;
    }

    private static List<Class<?>> publicApiTypes() throws Exception {
        var types = new ArrayList<Class<?>>();
        for (Path source : publicApiSources()) {
            Class<?> type = Class.forName(className(source));
            if (Modifier.isPublic(type.getModifiers())) {
                types.add(type);
            }
        }
        types.sort(Comparator.comparing(Class::getCanonicalName));
        return types;
    }

    private static List<Path> publicApiSources() throws IOException {
        var roots = List.of(Path.of("src/main/java/ai/doctruth"), Path.of("src/main/java/ai/doctruth/spi"));
        var sources = new ArrayList<Path>();
        for (Path root : roots) {
            try (var files = Files.list(root)) {
                files.filter(PublicApiSnapshotTest::isJavaSource).forEach(sources::add);
            }
        }
        return sources;
    }

    private static boolean isJavaSource(Path path) {
        return path.toString().endsWith(".java")
                && !path.getFileName().toString().equals("package-info.java");
    }

    private static String className(Path source) {
        String path = Path.of("src/main/java").relativize(source).toString();
        return path.substring(0, path.length() - ".java".length()).replace('/', '.');
    }

    private static void addType(List<String> lines, Class<?> type) {
        lines.add("TYPE " + kind(type) + " " + type.getCanonicalName() + modifiers(type.getModifiers()));
        addPermits(lines, type);
        addEnumConstants(lines, type);
        addRecordComponents(lines, type);
        addConstructors(lines, type);
        addMethods(lines, type);
        lines.add("");
    }

    private static String kind(Class<?> type) {
        if (type.isAnnotation()) return "annotation";
        if (type.isEnum()) return "enum";
        if (type.isRecord()) return "record";
        if (type.isInterface()) return "interface";
        return "class";
    }

    private static String modifiers(int modifiers) {
        String text = Modifier.toString(modifiers);
        return text.isBlank() ? "" : " [" + text + "]";
    }

    private static void addPermits(List<String> lines, Class<?> type) {
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null && permitted.length > 0) {
            lines.add("  permits " + joinTypes(permitted));
        }
    }

    private static void addEnumConstants(List<String> lines, Class<?> type) {
        Object[] constants = type.getEnumConstants();
        if (constants != null) {
            lines.add("  enum-constants "
                    + String.join(
                            ", ", Arrays.stream(constants).map(Object::toString).toList()));
        }
    }

    private static void addRecordComponents(List<String> lines, Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components != null) {
            lines.add("  record-components "
                    + String.join(
                            ", ",
                            Arrays.stream(components)
                                    .map(c -> typeName(c.getType()) + " " + c.getName())
                                    .toList()));
        }
    }

    private static void addConstructors(List<String> lines, Class<?> type) {
        Arrays.stream(type.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers()))
                .sorted(Comparator.comparing(PublicApiSnapshotTest::constructorSignature))
                .map(c -> "  ctor " + constructorSignature(c))
                .forEach(lines::add);
    }

    private static String constructorSignature(Constructor<?> constructor) {
        return constructor.getDeclaringClass().getSimpleName() + "(" + joinTypes(constructor.getParameterTypes()) + ")";
    }

    private static void addMethods(List<String> lines, Class<?> type) {
        Arrays.stream(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isBridge() && !m.isSynthetic())
                .sorted(Comparator.comparing(PublicApiSnapshotTest::methodSignature))
                .map(m -> "  method " + methodSignature(m))
                .forEach(lines::add);
    }

    private static String methodSignature(Method method) {
        return typeName(method.getReturnType())
                + " "
                + method.getName()
                + "("
                + joinTypes(method.getParameterTypes())
                + ")"
                + modifiers(method.getModifiers());
    }

    private static String joinTypes(Class<?>[] types) {
        return String.join(
                ", ", Arrays.stream(types).map(PublicApiSnapshotTest::typeName).toList());
    }

    private static String typeName(Class<?> type) {
        if (!type.isArray()) {
            return type.getCanonicalName();
        }
        return typeName(type.componentType()) + "[]";
    }
}
