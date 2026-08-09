package momoi.anno.mixin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method in a {@code @Mixin} class whose body is spliced into the target
 * class's constructor instead of being added as a normal method.
 *
 * <p>The annotated method's parameter signature MUST exactly match the target
 * constructor to augment (e.g. for {@code <init>(String, String, String)V}, declare
 * {@code fun hook(a: String?, b: String?, c: String?)}). The method's instructions are
 * appended to the end of that constructor (just before its {@code return-void}), with
 * the parameter registers mapping 1:1 — so {@code p1} in the hook is the constructor's
 * first argument.
 *
 * <p>The body MUST use only parameter registers ({@code p0..pN}) and no locals — i.e.
 * compile to {@code .locals 0}. Typical use is storing an argument into an added field:
 * {@code fun store(uid: String?, nick: String?, uin: String?) { this.myUid = uid }}.
 * This sidesteps the super-call/verifier problems of replacing a whole constructor.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConstructorHook {
}
