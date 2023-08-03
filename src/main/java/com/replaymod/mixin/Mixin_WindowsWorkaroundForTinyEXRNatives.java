package com.replaymod.mixin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.lwjgl.system.Library;
import org.lwjgl.system.Platform;
import org.lwjgl.util.tinyexr.TinyEXR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * It appears like natives on Windows cannot be loaded if one of their dependencies has already been loaded by a
 * different class loader. In our case we cannot load tinyexr (on the knot class loader) because lwjgl has already
 * been loaded on the system class loader.
 * <p>
 * If we force the tinyexr native to load on the system class loader (by calling `Library.loadSystem(absPath)`),
 * it'll load but we'll get an error when we call any of the native methods.
 * <p>
 * We can't really load TinyEXR itself via the system class loader because Java does not provide any methods for
 * modifying the system class path at runtime and we'd have to use JVM-specific hacks.
 * <p>
 * Strangely, if we use System.loadLibrary instead of System.load, then it all just works. This mixin implements
 * that workaround by finding MC's natives folder, extracting the dll from our jar into that folder and then replacing
 * the context class passed to Library.loadSystem (which it uses to find dlls in jars) with Library (which is on the
 * system class loader) so it cannot find the dll in our jar and falls back to using System.loadLibrary.
 */
@Mixin(value = TinyEXR.class)
public class Mixin_WindowsWorkaroundForTinyEXRNatives {
    @Unique
    private static final String LOAD_SYSTEM_CONSUMERS = "Lorg/lwjgl/system/Library;loadSystem(Ljava/util/function/Consumer;Ljava/util/function/Consumer;Ljava/lang/Class;Ljava/lang/String;)V";

    //TODO
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/system/Library;loadSystem(Ljava/util/function/Consumer;Ljava/util/function/Consumer;Ljava/lang/Class;Ljava/lang/String;)V"))
    private static Class<?> uglyWindowsHacks(Consumer<String> load, Consumer<String> loadLibrary, Class<?> context, String name) throws IOException {
        if (Platform.get() != Platform.WINDOWS) {
            return context; // works out of the box on linux
        }

        name = System.mapLibraryName(name);

        URL libURL = context.getClassLoader().getResource(name);
        if (libURL == null) {
            throw new UnsatisfiedLinkError("Failed to locate library: " + name);
        }

        String lwjglLibName = Library.JNI_LIBRARY_NAME;
        if (!lwjglLibName.endsWith(".dll")) {
            lwjglLibName = System.mapLibraryName(lwjglLibName);
        }

        String paths = System.getProperty("java.library.path");
        Path nativesDir = null;
        for (String dir : Pattern.compile(File.pathSeparator).split(paths)) {
            Path path = Paths.get(dir);
            if (Files.isReadable(path.resolve(lwjglLibName))) {
                nativesDir = path;
                break;
            }
        }
        if (nativesDir == null) {
            throw new UnsatisfiedLinkError("Failed to locate natives folder in " + paths);
        }

        Path libPath = nativesDir.resolve(name);
        try (InputStream source = libURL.openStream()) {
            Files.copy(source, libPath, StandardCopyOption.REPLACE_EXISTING);
        }

        return Library.class;
    }
}
