import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import java.io.File;
import java.security.MessageDigest;
import java.util.*;

/** Compare per-class method-instruction fingerprints between two dex sets. */
public class CompareDex {
    static String fp(ClassDef c) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Method m : c.getMethods()) {
            md.update(m.getName().getBytes());
            md.update(m.getReturnType().getBytes());
            for (Object p : m.getParameters()) md.update(p.toString().getBytes());
            MethodImplementation impl = m.getImplementation();
            if (impl != null) {
                for (Instruction ins : impl.getInstructions()) {
                    md.update(ins.getOpcode().name.getBytes());
                }
            }
        }
        return Base64.getEncoder().encodeToString(md.digest());
    }

    public static void main(String[] args) throws Exception {
        Opcodes op = Opcodes.forApi(19);
        Map<String, String> a = new HashMap<>();
        int split = 0;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--")) { split = i; break; }
        }
        if (split == 0) { System.err.println("usage: CompareDex <a dex...> -- <b dex...>"); return; }
        System.err.println("DEBUG args=" + java.util.Arrays.toString(args) + " split=" + split);
        for (int i = 0; i < split; i++) {
            DexFile d = DexFileFactory.loadDexFile(new File(args[i]), op);
            int n = 0;
            for (ClassDef c : d.getClasses()) a.put(c.getType(), fp(c));
            System.err.println("A load " + args[i] + " classes=" + d.getClasses().size());
        }
        Map<String, String> b = new HashMap<>();
        for (int i = split + 1; i < args.length; i++) {
            DexFile d2 = DexFileFactory.loadDexFile(new File(args[i]), op);
            for (ClassDef c : d2.getClasses()) b.put(c.getType(), fp(c));
        }

        int onlyA = 0, onlyB = 0, diff = 0, same = 0;
        List<String> diffTypes = new ArrayList<>();
        for (String t : a.keySet()) {
            if (!b.containsKey(t)) { onlyA++; continue; }
            if (a.get(t).equals(b.get(t))) same++;
            else { diff++; diffTypes.add(t); }
        }
        for (String t : b.keySet()) if (!a.containsKey(t)) onlyB++;
        System.out.println("aSize=" + a.size() + " bSize=" + b.size() +
            " same=" + same + " diff=" + diff + " onlyInOld=" + onlyA + " onlyInNew=" + onlyB);
        Collections.sort(diffTypes);
        for (String t : diffTypes) System.out.println("DIFF " + t);
    }
}
