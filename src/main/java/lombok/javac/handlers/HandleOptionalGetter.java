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
package lombok.javac.handlers;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Collection;

import lombok.AccessLevel;
import lombok.OptionalGetter;
import lombok.OptionalGetterConfigurationKeys;
import lombok.experimental.Accessors;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;

/**
 * Handles the {@code lombok.OptionalGetter} annotation for javac.
 * <p>
 * This is a close cousin of {@link HandleGetter}; the only differences are the return type
 * ({@code java.util.Optional<T>} instead of {@code T}) and the body
 * ({@code return java.util.Optional.ofNullable(this.field);}). Primitive fields are rejected:
 * a primitive is never absent, so wrapping it in an {@code Optional} would be meaningless.
 */
public class HandleOptionalGetter extends JavacAnnotationHandler<OptionalGetter> {
	private static final String OPTIONAL = "java.util.Optional";
	private static final String NODE_NOT_SUPPORTED_ERR = "@OptionalGetter is only supported on a class, an enum, or a field.";
	private static final String PRIMITIVE_ERR = "@OptionalGetter is not allowed on a primitive field: a primitive is never absent.";

	public void generateOptionalGetterForType(JavacNode typeNode, JavacNode errorNode, AccessLevel level, boolean checkForTypeLevelGetter, List<JCAnnotation> onMethod) {
		if (checkForTypeLevelGetter && hasAnnotation(OptionalGetter.class, typeNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}

		if (!isClassOrEnum(typeNode)) {
			errorNode.addError(NODE_NOT_SUPPORTED_ERR);
			return;
		}

		for (JavacNode field : typeNode.down()) {
			if (HandleGetter.fieldQualifiesForGetterGeneration(field)) generateOptionalGetterForField(field, errorNode, level, onMethod);
		}
	}

	/**
	 * Generates an optional getter on the stated field, unless it already has a {@code @Getter} or
	 * {@code @OptionalGetter} annotation (in which case that annotation's handler takes care of it), or
	 * the field is a primitive (which is silently skipped at the type level: {@code @OptionalGetter} on a
	 * class shouldn't fail just because the class happens to have a primitive field).
	 */
	public void generateOptionalGetterForField(JavacNode fieldNode, JavacNode source, AccessLevel level, List<JCAnnotation> onMethod) {
		if (hasAnnotation(lombok.Getter.class, fieldNode) || hasAnnotation(OptionalGetter.class, fieldNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}
		if (isPrimitive(fieldNode)) return;
		createGetterForField(level, fieldNode, source, false, onMethod);
	}

	@Override public void handle(AnnotationValues<OptionalGetter> annotation, JCAnnotation ast, JavacNode annotationNode) {
		Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
		deleteAnnotationIfNeccessary(annotationNode, OptionalGetter.class);
		deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
		JavacNode node = annotationNode.up();
		AccessLevel level = annotation.getInstance().value();

		if (level == AccessLevel.NONE || node == null) return;

		List<JCAnnotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@OptionalGetter(onMethod", annotationNode);

		switch (node.getKind()) {
		case FIELD:
			for (JavacNode fieldNode : fields) createGetterForField(level, fieldNode, annotationNode, true, onMethod);
			break;
		case TYPE:
			generateOptionalGetterForType(node, annotationNode, level, false, onMethod);
			break;
		}
	}

	private static boolean isPrimitive(JavacNode fieldNode) {
		return ((JCVariableDecl) fieldNode.get()).vartype instanceof JCPrimitiveTypeTree;
	}

	/** The configured accessor-name suffix (lombok.optionalGetter.suffix), defaulting to "Optional". */
	private static String optionalGetterSuffix(JavacNode node) {
		String suffix = node.getAst().readConfiguration(OptionalGetterConfigurationKeys.OPTIONAL_GETTER_SUFFIX);
		return suffix == null ? OptionalGetterConfigurationKeys.DEFAULT_SUFFIX : suffix;
	}

	// Nullness markers must not be copied onto the Optional accessor: its return is never null, and a
	// TYPE_USE @Nullable (e.g. JSpecify) placed on Optional<T> is a compile error ("not expected here").
	private static final java.util.Set<String> NULLITY_ANNOTATION_NAMES = new java.util.HashSet<String>(
		java.util.Arrays.asList("Nullable", "NonNull", "Nonnull", "CheckForNull"));

	private static List<JCAnnotation> stripNullityAnnotations(List<JCAnnotation> annotations) {
		List<JCAnnotation> kept = List.nil();
		for (JCAnnotation annotation : annotations) {
			if (!NULLITY_ANNOTATION_NAMES.contains(annotationSimpleName(annotation.annotationType))) kept = kept.append(annotation);
		}
		return kept;
	}

	private static String annotationSimpleName(com.sun.tools.javac.tree.JCTree annotationType) {
		if (annotationType instanceof JCFieldAccess) return ((JCFieldAccess) annotationType).name.toString();
		if (annotationType instanceof JCIdent) return ((JCIdent) annotationType).name.toString();
		return "";
	}

	public void createGetterForField(AccessLevel level, JavacNode fieldNode, JavacNode source, boolean whineIfExists, List<JCAnnotation> onMethod) {
		if (fieldNode.getKind() != Kind.FIELD || fieldNode.isEnumMember()) {
			source.addError(NODE_NOT_SUPPORTED_ERR);
			return;
		}
		if (isPrimitive(fieldNode)) {
			source.addError(PRIMITIVE_ERR);
			return;
		}

		JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();

		AnnotationValues<Accessors> accessors = getAccessorsForField(fieldNode);
		String suffix = optionalGetterSuffix(fieldNode);
		String baseName = toGetterName(fieldNode, accessors);

		if (baseName == null) {
			source.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		String methodName = baseName + suffix;

		for (String altName : toAllGetterNames(fieldNode, accessors)) {
			switch (methodExists(altName + suffix, fieldNode, false, 0)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					String altNameExpl = altName.equals(methodName) ? "" : String.format(" (%s)", altName);
					source.addWarning(String.format("Not generating %s(): A method with that name already exists%s", methodName, altNameExpl));
				}
				return;
			default:
			case NOT_EXISTS:
				//continue scanning the other alt names.
			}
		}

		long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);
		injectMethod(fieldNode.up(), createGetter(access, fieldNode, fieldNode.getTreeMaker(), source, onMethod));
	}

	public JCMethodDecl createGetter(long access, JavacNode field, JavacTreeMaker treeMaker, JavacNode source, List<JCAnnotation> onMethod) {
		JCVariableDecl fieldNode = (JCVariableDecl) field.get();

		// Return type: java.util.Optional<fieldType>. Primitives are rejected earlier, so the field type is used verbatim.
		JCExpression valueType = fieldNode.type != null ? treeMaker.Type(fieldNode.type) : cloneType(treeMaker, fieldNode.vartype, source);
		JCExpression methodType = treeMaker.TypeApply(chainDotsString(field, OPTIONAL), List.of(valueType));

		AnnotationValues<Accessors> accessors = getAccessorsForField(field);
		Name methodName = field.toName(toGetterName(field, accessors) + optionalGetterSuffix(field));
		boolean makeFinal = shouldMakeFinal(field, accessors);

		// return java.util.Optional.ofNullable(this.field);
		JCExpression fieldRef = createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD);
		JCExpression wrapped = treeMaker.Apply(List.<JCExpression>nil(), treeMaker.Select(chainDotsString(field, OPTIONAL), field.toName("ofNullable")), List.of(fieldRef));
		JCBlock methodBody = treeMaker.Block(0, List.<JCStatement>of(treeMaker.Return(wrapped)));

		List<JCTypeParameter> methodGenericParams = List.nil();
		List<JCVariableDecl> parameters = List.nil();
		List<JCExpression> throwsClauses = List.nil();
		JCExpression annotationMethodDefaultValue = null;

		List<JCAnnotation> copyableAnnotations = stripNullityAnnotations(findCopyableAnnotations(field));

		// Copying Jackson annotations is required for fluent accessors (otherwise Jackson would not find the accessor).
		boolean fluent = accessors.isExplicit("fluent");
		Boolean fluentConfig = field.getAst().readConfiguration(lombok.ConfigurationKeys.ACCESSORS_FLUENT);
		if (fluentConfig != null && fluentConfig) fluent = fluentConfig;
		List<JCAnnotation> copyableToGetterAnnotations = copyAnnotations(findCopyableToGetterAnnotations(field, fluent), treeMaker);

		List<JCAnnotation> annsOnMethod = copyAnnotations(onMethod, treeMaker).appendList(copyableAnnotations).appendList(copyableToGetterAnnotations);
		if (field.isFinal()) {
			if (getCheckerFrameworkVersion(field).generatePure()) annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genTypeRef(field, CheckerFrameworkVersion.NAME__PURE), List.<JCExpression>nil()));
		} else {
			if (getCheckerFrameworkVersion(field).generateSideEffectFree()) annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genTypeRef(field, CheckerFrameworkVersion.NAME__SIDE_EFFECT_FREE), List.<JCExpression>nil()));
		}
		if (isFieldDeprecated(field)) annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));

		if (makeFinal) access |= Flags.FINAL;
		JCMethodDecl decl = recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName, methodType,
			methodGenericParams, parameters, throwsClauses, methodBody, annotationMethodDefaultValue), source);

		copyJavadoc(field, decl, CopyJavadoc.GETTER);
		return decl;
	}
}
