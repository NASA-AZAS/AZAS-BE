package azas.domain.analysis.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class EngineFingerprint {
    private EngineFingerprint() {}

    static String of(Class<?>... roots) {
        List<Class<?>> types = new ArrayList<>();

        for (Class<?> root : roots) {
            collect(root, types);
        }

        types.sort(Comparator.comparing(Class::getName));

        try (var bytes = new ByteArrayOutputStream()) {
            for (Class<?> type : types) {
                try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                    if (stream == null) {
                        throw new IOException("Missing analysis implementation class");
                    }

                    bytes.write(type.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    bytes.write(0);
                    bytes.write(stream.readAllBytes());
                }
            }

            return HorizonsClient.sha256(bytes.toByteArray());
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static void collect(Class<?> type, List<Class<?>> types) {
        types.add(type);

        for (Class<?> child : type.getDeclaredClasses()) collect(child, types);
    }
}
