import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import lanchon.multidexlib2.BasicDexFileNamer;
import lanchon.multidexlib2.MultiDexIO;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replace one class in a target dex with the version from a source dex, keeping everything else
 * byte-identical. Usage: SwapClass <target.dex> <source.dex> <output.dex> <classType...>
 */
public class SwapClass {
    public static void main(String[] args) throws Exception {
        Opcodes op = Opcodes.forApi(19);
        DexFile target = DexFileFactory.loadDexFile(new File(args[0]), op);
        DexFile source = DexFileFactory.loadDexFile(new File(args[1]), op);
        Set<String> types = new HashSet<>();
        for (int i = 3; i < args.length; i++) types.add(args[i]);

        ClassDef replacement = null;
        for (ClassDef c : source.getClasses()) {
            if (types.contains(c.getType())) replacement = c;
        }
        if (replacement == null) throw new IllegalStateException("class not found in source: " + types);

        List<ClassDef> out = new ArrayList<>();
        int replaced = 0;
        for (ClassDef c : target.getClasses()) {
            if (types.contains(c.getType())) { out.add(replacement); replaced++; }
            else out.add(c);
        }
        System.out.println("replaced=" + replaced + " targetClasses=" + target.getClasses().size());
        File outDir = new File(args[2] + ".dir").getAbsoluteFile();
        outDir.mkdirs();
        MultiDexIO.writeDexFile(true, 1, outDir, new BasicDexFileNamer(),
                new ImmutableDexFile(op, out),
                lanchon.multidexlib2.DexIO.DEFAULT_MAX_DEX_POOL_SIZE, null);
        File[] files = outDir.listFiles((d, n) -> n.endsWith(".dex"));
        if (files != null && files.length == 1) {
            files[0].renameTo(new File(args[2]));
        } else {
            System.out.println("WARNING: output dex count=" + (files == null ? 0 : files.length));
        }
    }
}
