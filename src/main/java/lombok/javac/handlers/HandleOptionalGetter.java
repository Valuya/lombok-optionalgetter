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
import static lombok.javac.Javac.*;
import static lombok.javac.JavacTreeMaker.TypeTag.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import lombok.AccessLevel;
import lombok.OptionalGetter;
import lombok.experimental.Accessors;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.JavacTreeMaker.TypeTag;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
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
 * ({@code return java.util.Optional.ofNullable(this.field);}).
 */
public class HandleOptionalGetter extends JavacAnnotationHandler<OptionalGetter> {
	private static final String OPTIONAL = "java.util.Optional";
	private static final String GETTER_NODE_NOT_SUPPORTED_ERR = "@OptionalGetter is only supported on a class, an enum, or a field.";

	public static final Map<TypeTag, String> TYPE_MAP;
	static {
		Map<TypeTag, String> m = new HashMap<TypeTag, String>();
		m.put(CTC_INT, "Integer");
		m.put(CTC_DOUBLE, "Double");
		m.put(CTC_FLOAT, "Float");
		m.put(CTC_SHORT, "Short");
		m.put(CTC_BYTE, "Byte");
		m.put(CTC_LONG, "Long");
		m.put(CTC_BOOLEAN, "Boolean");
		m.put(CTC_CHAR, "Character");
		TYPE_MAP = Collections.unmodifiableMap(m);
	}

	public void generateOptionalGetterForType(JavacNode typeNode, JavacNode errorNode, AccessLevel level, boolean checkForTypeLevelGetter, List<JCAnnotation> onMethod) {
		if (checkForTypeLevelGetter) {
			if (hasAnnotation(OptionalGetter.class, typeNode)) {
				//The annotation will make it happen, so we can skip it.
				return;
			}
		}

		if (!isClassOrEnum(typeNode)) {
			errorNode.addError(GETTER_NODE_NOT_SUPPORTED_ERR);
			return;
		}

		for (JavacNode field : typeNode.down()) {
			if (HandleGetter.fieldQualifiesForGetterGeneration(field)) generateOptionalGetterForField(field, errorNode, level, onMethod);
		}
	}

	/**
	 * Generates an optional getter on the stated field, unless it already has a {@code @Getter} or
	 * {@code @OptionalGetter} annotation (in which case that annotation's handler takes care of it).
	 */
	public void generateOptionalGetterForField(JavacNode fieldNode, JavacNode source, AccessLevel level, List<JCAnnotation> onMethod) {
		if (hasAnnotation(lombok.Getter.class, fieldNode) || hasAnnotation(OptionalGetter.class, fieldNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}
		createGetterForField(level, fieldNode, source, false, onMethod);
	}

	@Override public void handle(AnnotationValues<OptionalGetter> annotation, JCAnnotation ast, JavacNode annotationNode) {
		Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
		deleteAnnotationIfNeccessary(annotationNode, OptionalGetter.class);
		deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
		JavacNode node = annotationNode.up();
		AccessLevel level = annotation.getInstance().value();

		if (level == AccessLevel.NONE) return;
		if (node == null) return;

		List<JCAnnotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@OptionalGetter(onMethod", annotationNode);

		switch (node.getKind()) {
		case FIELD:
			createGetterForFields(level, fields, annotationNode, true, onMethod);
			break;
		case TYPE:
			generateOptionalGetterForType(node, annotationNode, level, false, onMethod);
			break;
		}
	}

	public void createGetterForFields(AccessLevel level, Collection<JavacNode> fieldNodes, JavacNode errorNode, boolean whineIfExists, List<JCAnnotation> onMethod) {
		for (JavacNode fieldNode : fieldNodes) {
			createGetterForField(level, fieldNode, errorNode, whineIfExists, onMethod);
		}
	}

	public void createGetterForField(AccessLevel level, JavacNode fieldNode, JavacNode source, boolean whineIfExists, List<JCAnnotation> onMethod) {
		if (fieldNode.getKind() != Kind.FIELD || fieldNode.isEnumMember()) {
			source.addError(GETTER_NODE_NOT_SUPPORTED_ERR);
			return;
		}

		JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();

		AnnotationValues<Accessors> accessors = getAccessorsForField(fieldNode);
		String methodName = toGetterName(fieldNode, accessors);

		if (methodName == null) {
			source.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
			return;
		}

		for (String altName : toAllGetterNames(fieldNode, accessors)) {
			switch (methodExists(altName, fieldNode, false, 0)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					String altNameExpl = "";
					if (!altName.equals(methodName)) altNameExpl = String.format(" (%s)", altName);
					source.addWarning(
						String.format("Not generating %s(): A method with that name already exists%s", methodName, altNameExpl));
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

		// The type argument of Optional<...>: the field's own type, boxed if primitive.
		JCExpression valueType;
		boolean primitive = fieldNode.vartype instanceof JCPrimitiveTypeTree && TYPE_MAP.containsKey(typeTag(fieldNode.vartype));
		if (primitive) {
			valueType = genJavaLangTypeRef(field, TYPE_MAP.get(typeTag(fieldNode.vartype)));
		} else {
			valueType = copyType(treeMaker, fieldNode, source);
		}

		// Return type: java.util.Optional<valueType>
		JCExpression methodType = treeMaker.TypeApply(chainDotsString(field, OPTIONAL), List.of(valueType));

		AnnotationValues<Accessors> accessors = JavacHandlerUtil.getAccessorsForField(field);
		Name methodName = field.toName(toGetterName(field, accessors));
		boolean makeFinal = shouldMakeFinal(field, accessors);

		// return java.util.Optional.ofNullable(this.field);  (primitives can't be null: Optional.of)
		JCExpression fieldRef = createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD);
		Name factory = field.toName(primitive ? "of" : "ofNullable");
		JCExpression wrapped = treeMaker.Apply(List.<JCExpression>nil(), treeMaker.Select(chainDotsString(field, OPTIONAL), factory), List.of(fieldRef));
		List<JCStatement> statements = List.<JCStatement>of(treeMaker.Return(wrapped));

		JCBlock methodBody = treeMaker.Block(0, statements);

		List<JCTypeParameter> methodGenericParams = List.nil();
		List<JCVariableDecl> parameters = List.nil();
		List<JCExpression> throwsClauses = List.nil();
		JCExpression annotationMethodDefaultValue = null;

		List<JCAnnotation> copyableAnnotations = findCopyableAnnotations(field);

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

	public JCExpression copyType(JavacTreeMaker treeMaker, JCVariableDecl fieldNode, JavacNode source) {
		return fieldNode.type != null ? treeMaker.Type(fieldNode.type) : cloneType(treeMaker, fieldNode.vartype, source);
	}
}
