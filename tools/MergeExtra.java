import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import lanchon.multidexlib2.BasicDexFileNamer;
import lanchon.multidexlib2.MultiDexIO;
import java.io.File;
import java.util.*;

/**
 * Merge the pool-overflow dex (orig2/classes2.dex, orig3/classes2.dex) into the new-classes
 * dex so the final layout keeps exactly 5 dex (original 4 + one module dex).
 */
public class MergeExtra {
    public static void main(String[] args) throws Exception {
        Opcodes opcodes = Opcodes.forApi(19);
        List<ClassDef> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String path : args) {
            DexFile d = DexFileFactory.loadDexFile(new File(path), opcodes);
            for (ClassDef c : d.getClasses()) {
                if (seen.add(c.getType())) all.add(c);
            }
        }
        System.out.println("total classes: " + all.size());
        File out = new File("out_merged_extra");
        out.mkdirs();
        MultiDexIO.writeDexFile(true, 1, out, new BasicDexFileNamer(),
                new ImmutableDexFile(opcodes, all),
                lanchon.multidexlib2.DexIO.DEFAULT_MAX_DEX_POOL_SIZE, null);
        File[] files = out.listFiles((d, n) -> n.endsWith(".dex"));
        System.out.println("merged dex files: " + (files == null ? 0 : files.length));
    }
}
