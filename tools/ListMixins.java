import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.AnnotationElement;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue;
import com.android.tools.smali.dexlib2.iface.Method;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class ListMixins {
    public static void main(String[] args) throws Exception {
        Opcodes opcodes = Opcodes.forApi(19);
        com.android.tools.smali.dexlib2.iface.DexFile dex =
                DexFileFactory.loadDexFile(new File(args[0]), opcodes);
        Set<String> printed = new HashSet<>();
        for (ClassDef c : dex.getClasses()) {
            for (Annotation a : c.getAnnotations()) {
                if ("Lmomoi/anno/mixin/Mixin;".equals(a.getType())) {
                    System.out.println(c.getType() + " -> " + c.getSuperclass());
                    printed.add(c.getType() + "|" + c.getSuperclass());
                }
            }
            for (Method m : c.getMethods()) {
                for (Annotation a : m.getAnnotations()) {
                    if ("Lmomoi/anno/mixin/StaticHook;".equals(a.getType())) {
                        for (AnnotationElement el : a.getElements()) {
                            EncodedValue v = el.getValue();
                            if (v instanceof TypeEncodedValue) {
                                String t = ((TypeEncodedValue) v).getValue();
                                System.out.println("STATIC " + c.getType() + " -> " + t);
                            }
                        }
                    }
                }
            }
        }
    }
}
