package no.beint.thim.example;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.CodeAttribute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedRendererTest {
    @Test
    void partitionsLargeRenderersBelowTheHotSpotHugeMethodThreshold() throws IOException {
        var resource = "/no/beint/thim/example/generated/no_beint_thim_example_page_LargePageThimRenderer.class";
        byte[] bytes;
        try (var input = getClass().getResourceAsStream(resource)) {
            bytes = input.readAllBytes();
        }
        var methods = ClassFile.of().parse(bytes).methods().stream()
                .filter(method -> method.methodName().stringValue().startsWith("render"))
                .toList();

        assertTrue(methods.size() > 2, "large fixture should exercise renderer partitioning");
        methods.forEach(method -> assertTrue(
                ((CodeAttribute) method.code().orElseThrow()).codeLength() < 8_000,
                () -> method.methodName().stringValue() + " exceeds HotSpot's huge-method threshold"));
    }
}
