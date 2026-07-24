/*
 * Copyright (C) 2009-2025 The Project Lombok Authors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on any field to make lombok build a getter that returns an {@link java.util.Optional} wrapping the field value.
 * <p>
 * This is a variant of {@link Getter}: instead of returning the field's own type {@code T}, the generated
 * accessor returns {@code java.util.Optional<T>}. The value is wrapped with {@code Optional.ofNullable(...)},
 * so a {@code null} field yields an empty {@code Optional} rather than a {@code null} return.
 * <p>
 * Primitive fields are <b>not</b> allowed: a primitive is never absent, so wrapping it in an {@code Optional}
 * would be meaningless. Placing {@code @OptionalGetter} directly on a primitive field is a compile error; when
 * the annotation is applied to a whole class, primitive fields are simply skipped.
 * <p>
 * The accessor name carries a configurable suffix, {@code Optional} by default, so it does not collide with a
 * plain {@code @Getter} on the same field. Set {@code lombok.optionalGetter.suffix} in {@code lombok.config}
 * to change it (an empty value produces the plain {@code getFoo()} name).
 * <p>
 * Example:
 * <pre>
 *     private &#64;OptionalGetter String foo;
 * </pre>
 *
 * will generate (with the default suffix):
 *
 * <pre>
 *     public java.util.Optional&lt;String&gt; getFooOptional() {
 *         return java.util.Optional.ofNullable(this.foo);
 *     }
 * </pre>
 * <p>
 * This annotation can also be applied to a class, in which case it'll be as if all non-static fields that don't already
 * have a {@code @OptionalGetter} (or {@code @Getter}) annotation have the annotation.
 */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface OptionalGetter {
	/**
	 * If you want your getter to be non-public, you can specify an alternate access level here.
	 *
	 * @return The getter method will be generated with this access modifier.
	 */
	lombok.AccessLevel value() default lombok.AccessLevel.PUBLIC;

	/**
	 * Any annotations listed here are put on the generated method.
	 * The syntax for this feature depends on JDK version (nothing we can do about that; it's to work around javac bugs).<br>
	 * up to JDK7:<br>
	 *  {@code @OptionalGetter(onMethod=@__({@AnnotationsGoHere}))}<br>
	 * from JDK8:<br>
	 *  {@code @OptionalGetter(onMethod_={@AnnotationsGohere})} // note the underscore after {@code onMethod}.
	 *
	 * @return List of annotations to apply to the generated getter method.
	 */
	AnyAnnotation[] onMethod() default {};

	/**
	 * Placeholder annotation to enable the placement of annotations on the generated code.
	 * @deprecated Don't use this annotation, ever - Read the documentation.
	 */
	@Deprecated
	@Retention(RetentionPolicy.SOURCE)
	@Target({})
	@interface AnyAnnotation {}
}
