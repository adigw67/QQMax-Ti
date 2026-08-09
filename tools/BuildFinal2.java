import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import lanchon.multidexlib2.BasicDexFileNamer;
import lanchon.multidexlib2.MultiDexIO;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Preserve the ORIGINAL 4-dex layout:
 *  - dex1..4 = original classes.dex..classes4.dex with hook-modified targets substituted
 *  - dex5..N = new classes (hooks/ktx/coroutines/stdlib) not present in the original APK
 */
public class BuildFinal2 {
    public static void main(String[] args) throws Exception {
        Opcodes opcodes = Opcodes.forApi(19);
        Set<String> targets = new HashSet<>(Files.readAllLines(
                new File("/tmp/all_targets.txt").toPath()));
        String origDir = args[0];                        // dir with original classes.dex..classes4.dex
        List<String> allOutputDex = Arrays.asList(Arrays.copyOfRange(args, 1, args.length));

        Map<String, ClassDef> modified = new HashMap<>();
        for (String path : allOutputDex) {
            DexFile d = DexFileFactory.loadDexFile(new File(path), opcodes);
            for (ClassDef c : d.getClasses()) {
                if (targets.contains(c.getType())) modified.put(c.getType(), c);
            }
        }
        System.out.println("modified targets found: " + modified.size());

        File outDir = new File("out_final2");
        outDir.mkdirs();
        Set<String> allOriginal = new HashSet<>();
        for (int i = 1; i <= 4; i++) {
            String name = i == 1 ? "classes.dex" : "classes" + i + ".dex";
            File f = new File(origDir, name);
            if (!f.exists()) continue;
            DexFile orig = DexFileFactory.loadDexFile(f, opcodes);
            List<ClassDef> list = new ArrayList<>();
            int replaced = 0;
            for (ClassDef c : orig.getClasses()) {
                ClassDef repl = modified.get(c.getType());
                if (repl != null) replaced++;
                list.add(repl != null ? repl : c);
                allOriginal.add(c.getType());
            }
            File sub = new File(outDir, "orig" + i);
            sub.mkdirs();
            MultiDexIO.writeDexFile(true, 1, sub, new BasicDexFileNamer(),
                    new ImmutableDexFile(opcodes, list),
                    lanchon.multidexlib2.DexIO.DEFAULT_MAX_DEX_POOL_SIZE, null);
            System.out.println("wrote orig" + i + " classes=" + list.size() + " replaced=" + replaced);
        }

        List<ClassDef> newClasses = new ArrayList<>();
        Set<String> seen = new HashSet<>(allOriginal);
        for (String path : allOutputDex) {
            DexFile d = DexFileFactory.loadDexFile(new File(path), opcodes);
            for (ClassDef c : d.getClasses()) {
                if (seen.add(c.getType())) newClasses.add(c);
            }
        }
        System.out.println("new classes: " + newClasses.size());
        File subNew = new File(outDir, "new");
        subNew.mkdirs();
        MultiDexIO.writeDexFile(true, 1, subNew, new BasicDexFileNamer(),
                new ImmutableDexFile(opcodes, newClasses),
                lanchon.multidexlib2.DexIO.DEFAULT_MAX_DEX_POOL_SIZE, null);
        File[] files = subNew.listFiles((d, n) -> n.endsWith(".dex"));
        System.out.println("total new dex files: " + (files == null ? 0 : files.length));
        System.out.println("dir=" + outDir.getAbsolutePath());
    }
}
