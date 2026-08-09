import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import java.io.File;

/** Print every class type in a dex file (or the first dex of a dir, per caller usage). */
public class DexTypes {
    public static void main(String[] args) throws Exception {
        Opcodes opcodes = Opcodes.forApi(19);
        for (String arg : args) {
            File f = new File(arg);
            DexFile d = DexFileFactory.loadDexFile(f, opcodes);
            for (ClassDef c : d.getClasses()) {
                System.out.println(c.getType());
            }
        }
    }
}
