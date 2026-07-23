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
package lombok.eclipse.handlers;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.eclipse.Eclipse.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.AccessLevel;
import lombok.OptionalGetter;
import lombok.experimental.Accessors;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;

/**
 * Handles the {@code lombok.OptionalGetter} annotation for eclipse.
 * <p>
 * Cousin of {@link HandleGetter}: the generated accessor returns {@code java.util.Optional<T>} and its body is
 * {@code return java.util.Optional.ofNullable(this.field);} (or {@code Optional.of(...)} for a primitive field).
 */
public class HandleOptionalGetter extends EclipseAnnotationHandler<OptionalGetter> {
	private static final char[][] OPTIONAL = fromQualifiedName("java.util.Optional");
	private static final char[] OF_NULLABLE = "ofNullable".toCharArray();
	private static final char[] OF = "of".toCharArray();
	private static final String GETTER_NODE_NOT_SUPPORTED_ERR = "@OptionalGetter is only supported on a class, an enum, or a field.";

	public static final Map<String, char[][]> TYPE_MAP;
	static {
		Map<String, char[][]> m = new HashMap<String, char[][]>();
		m.put("int", fromQualifiedName("java.lang.Integer"));
		m.put("double", fromQualifiedName("java.lang.Double"));
		m.put("float", fromQualifiedName("java.lang.Float"));
		m.put("short", fromQualifiedName("java.lang.Short"));
		m.put("byte", fromQualifiedName("java.lang.Byte"));
		m.put("long", fromQualifiedName("java.lang.Long"));
		m.put("boolean", fromQualifiedName("java.lang.Boolean"));
		m.put("char", fromQualifiedName("java.lang.Character"));
		TYPE_MAP = Collections.unmodifiableMap(m);
	}

	public boolean generateOptionalGetterForType(EclipseNode typeNode, EclipseNode pos, AccessLevel level, boolean checkForTypeLevelGetter, List<Annotation> onMethod) {
		if (checkForTypeLevelGetter) {
			if (hasAnnotation(OptionalGetter.class, typeNode)) return true;
		}

		if (!isClassOrEnum(typeNode)) {
			pos.addError(GETTER_NODE_NOT_SUPPORTED_ERR);
			return false;
		}

		for (EclipseNode field : typeNode.down()) {
			if (HandleGetter.fieldQualifiesForGetterGeneration(field)) generateOptionalGetterForField(field, pos.get(), level, onMethod);
		}
		return true;
	}

	public void generateOptionalGetterForField(EclipseNode fieldNode, ASTNode pos, AccessLevel level, List<Annotation> onMethod) {
		if (hasAnnotation(lombok.Getter.class, fieldNode) || hasAnnotation(OptionalGetter.class, fieldNode)) return;
		createGetterForField(level, fieldNode, fieldNode, pos, false, onMethod);
	}

	public void handle(AnnotationValues<OptionalGetter> annotation, Annotation ast, EclipseNode annotationNode) {
		EclipseNode node = annotationNode.up();
		AccessLevel level = annotation.getInstance().value();

		if (level == AccessLevel.NONE) return;
		if (node == null) return;

		List<Annotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@OptionalGetter(onMethod", annotationNode);

		switch (node.getKind()) {
		case FIELD:
			createGetterForFields(level, annotationNode.upFromAnnotationToFields(), annotationNode, annotationNode.get(), true, onMethod);
			break;
		case TYPE:
			generateOptionalGetterForType(node, annotationNode, level, false, onMethod);
			break;
		}
	}

	public void createGetterForFields(AccessLevel level, Collection<EclipseNode> fieldNodes, EclipseNode errorNode, ASTNode source, boolean whineIfExists, List<Annotation> onMethod) {
		for (EclipseNode fieldNode : fieldNodes) {
			createGetterForField(level, fieldNode, errorNode, source, whineIfExists, onMethod);
		}
	}

	public void createGetterForField(AccessLevel level, EclipseNode fieldNode, EclipseNode errorNode, ASTNode source, boolean whineIfExists, List<Annotation> onMethod) {
		if (fieldNode.getKind() != Kind.FIELD || fieldNode.isEnumMember()) {
			errorNode.addError(GETTER_NODE_NOT_SUPPORTED_ERR);
			return;
		}

		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		AnnotationValues<Accessors> accessors = getAccessorsForField(fieldNode);
		// The Optional getter always returns a reference type, so the accessor is a plain "get..." (never boolean "is...").
		String getterName = toGetterName(fieldNode, false, accessors);

		if (getterName == null) {
			errorNode.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
			return;
		}

		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);

		for (String altName : toAllGetterNames(fieldNode, false, accessors)) {
			switch (methodExists(altName, fieldNode, false, 0)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					String altNameExpl = "";
					if (!altName.equals(getterName)) altNameExpl = String.format(" (%s)", altName);
					errorNode.addWarning(
						String.format("Not generating %s(): A method with that name already exists%s", getterName, altNameExpl));
				}
				return;
			default:
			case NOT_EXISTS:
				//continue scanning the other alt names.
			}
		}

		MethodDeclaration method = createGetter((TypeDeclaration) fieldNode.up().get(), fieldNode, getterName, modifier, source, onMethod);
		injectMethod(fieldNode.up(), method);
	}

	public MethodDeclaration createGetter(TypeDeclaration parent, EclipseNode fieldNode, String name, int modifier, ASTNode source, List<Annotation> onMethod) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;

		// The type argument of Optional<...>: the field's own type, boxed if primitive.
		TypeReference valueType;
		boolean primitive = false;
		if (field.type != null && field.type.dimensions() == 0) {
			char[][] boxed = TYPE_MAP.get(new String(field.type.getLastToken()));
			if (boxed != null && field.type.getTypeName().length == 1) {
				valueType = new QualifiedTypeReference(boxed, poss(source, boxed.length));
				primitive = true;
			} else {
				valueType = copyType(field.type, source);
			}
		} else {
			valueType = copyType(field.type, source);
		}

		// Return type: java.util.Optional<valueType>
		TypeReference[][] typeArgs = new TypeReference[OPTIONAL.length][];
		typeArgs[OPTIONAL.length - 1] = new TypeReference[] {valueType};
		TypeReference returnType = new ParameterizedQualifiedTypeReference(OPTIONAL, typeArgs, 0, poss(source, OPTIONAL.length));
		setGeneratedBy(returnType, source);

		// return java.util.Optional.ofNullable(this.field);  (primitives can't be null: Optional.of)
		MessageSend call = new MessageSend();
		call.sourceStart = pS; call.sourceEnd = pE;
		call.statementEnd = pE;
		call.nameSourcePosition = p;
		call.receiver = generateQualifiedNameRef(source, OPTIONAL);
		call.selector = primitive ? OF : OF_NULLABLE;
		call.arguments = new Expression[] {createFieldAccessor(fieldNode, FieldAccess.ALWAYS_FIELD, source)};
		setGeneratedBy(call, source);
		Statement returnStatement = new ReturnStatement(call, pS, pE);
		setGeneratedBy(returnStatement, source);

		AnnotationValues<Accessors> accessors = getAccessorsForField(fieldNode);
		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		if (shouldMakeFinal(fieldNode, accessors)) modifier |= ClassFileConstants.AccFinal;
		method.modifiers = modifier;
		method.returnType = returnType;
		method.annotations = null;
		method.arguments = null;
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		method.statements = new Statement[] {returnStatement};

		/* Generate annotations that must be put on the generated method, and attach them. */ {
			Annotation[] deprecated = null, checkerFramework = null;
			if (isFieldDeprecated(fieldNode)) deprecated = new Annotation[] {generateDeprecatedAnnotation(source)};
			if (fieldNode.isFinal()) {
				if (getCheckerFrameworkVersion(fieldNode).generatePure()) checkerFramework = new Annotation[] {generateNamedAnnotation(source, CheckerFrameworkVersion.NAME__PURE)};
			} else {
				if (getCheckerFrameworkVersion(fieldNode).generateSideEffectFree()) checkerFramework = new Annotation[] {generateNamedAnnotation(source, CheckerFrameworkVersion.NAME__SIDE_EFFECT_FREE)};
			}

			// Copying Jackson annotations is required for fluent accessors (otherwise Jackson would not find the accessor).
			boolean fluent = accessors.isExplicit("fluent");
			Boolean fluentConfig = fieldNode.getAst().readConfiguration(lombok.ConfigurationKeys.ACCESSORS_FLUENT);
			if (fluentConfig != null && fluentConfig) fluent = fluentConfig;

			method.annotations = copyAnnotations(source,
				onMethod.toArray(new Annotation[0]),
				findCopyableAnnotations(fieldNode),
				findCopyableToGetterAnnotations(fieldNode, fluent),
				checkerFramework,
				deprecated);
		}

		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		copyJavadoc(fieldNode, method, CopyJavadoc.GETTER);
		return method;
	}
}
