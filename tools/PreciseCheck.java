import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import java.io.File;

/** Check whether M3QQEditText's ripple$default reference matches M3's definition. */
public class PreciseCheck {
    static final String DEF = "Lmomoi/mod/qqpro/lib/material/M3;";
    static final String REF = "Lmomoi/mod/qqpro/lib/material/M3QQEditText;";
    static final String RIPPLE = "ripple$default";

    public static void main(String[] args) throws Exception {
        Opcodes op = Opcodes.forApi(19);
        for (String arg : args) {
            DexFile d = DexFileFactory.loadDexFile(new File(arg), op);
            for (ClassDef c : d.getClasses()) {
                if (c.getType().equals(DEF)) {
                    for (Method m : c.getMethods()) {
                        if (m.getName().equals(RIPPLE)) {
                            System.out.println("DEF " + m.getName() + " -> " + m.getReturnType());
                        }
                    }
                }
                if (c.getType().equals(REF)) {
                    for (Method m : c.getMethods()) {
                        MethodImplementation impl = m.getImplementation();
                        if (impl == null) continue;
                        for (Instruction ins : impl.getInstructions()) {
                            if (ins instanceof ReferenceInstruction) {
                                Object ref = ((ReferenceInstruction) ins).getReference();
                                if (ref instanceof MethodReference) {
                                    MethodReference mr = (MethodReference) ref;
                                    if (mr.getName().equals(RIPPLE)) {
                                        System.out.println("REF " + mr.getName() + " -> " + mr.getReturnType());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
