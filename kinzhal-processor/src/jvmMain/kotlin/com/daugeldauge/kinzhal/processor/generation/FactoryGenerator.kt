package com.daugeldauge.kinzhal.processor.generation

import com.daugeldauge.kinzhal.annotations.Scope
import com.daugeldauge.kinzhal.processor.findAnnotation
import com.daugeldauge.kinzhal.processor.model.FactoryBinding
import com.daugeldauge.kinzhal.processor.model.Key
import com.daugeldauge.kinzhal.processor.resolveToUnderlying
import com.daugeldauge.kinzhal.processor.toKey
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.*


internal fun generateFactory(
    codeGenerator: CodeGenerator,
    injectableKey: Key,
    annotations: Sequence<KSAnnotation>,
    sourceDeclaration: KSFunctionDeclaration,
    addCreateInstanceCall: CodeBlock.Builder.() -> Unit,
    packageName: String,
    factoryBaseName: String,
): FactoryBinding {

    val dependencies = sourceDeclaration.parameters.map {
        ("${it.name!!.asString()}Provider") to it.type.toKey(it.annotations)
    }

    val providers: List<Pair<String, TypeName>> = dependencies.map { (providerName, key) ->
        providerName to LambdaTypeName.get(returnType = key.asTypeName())
    }

    val containingFile = sourceDeclaration.containingFile!!

    val factoryName = factoryBaseName + "_Factory"
    codeGenerator.newFile(
        dependenciesAggregating = false,
        dependencies = arrayOf(containingFile),
        packageName = packageName,
        fileName = factoryName,
    ) {

        val properties = providers.map { (name, type) ->
            PropertySpec.builder(
                name,
                type,
                KModifier.PRIVATE,
            ).initializer(name).build()
        }

        addType(
            TypeSpec.classBuilder(factoryName)
                .addModifiers(KModifier.INTERNAL)
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameters(
                            providers.map { (name, type) -> ParameterSpec.builder(name, type).build() }
                        )
                        .build()
                )
                .addSuperinterface(LambdaTypeName.get(returnType = injectableKey.asTypeName()))
                .addProperties(properties)
                .addFunction(
                    FunSpec.builder("invoke")
                        .returns(injectableKey.asTypeName())
                        .addModifiers(KModifier.OVERRIDE)
                        .addCode(CodeBlock.builder().apply {
                            add("return ")
                            addCreateInstanceCall()

                            if (properties.isEmpty()) {
                                add("()")
                            } else {
                                add("(\n")
                                withIndent {
                                    properties.forEach {
                                        add("%N(),\n", it)
                                    }
                                }
                                add(")")
                            }
                        }.build())
                        .build()
                )
                .build()
        )
    }

    return FactoryBinding(
        key = injectableKey,
        declaration = sourceDeclaration,
        containingFile = containingFile,
        scoped = annotations.mapNotNull {
            it.annotationType.resolveToUnderlying().declaration.findAnnotation<Scope>()?.annotationType?.resolveToUnderlying()
        }.toList().isNotEmpty(),
        dependencies = dependencies.map { it.second },
        factoryName = factoryName,
        factoryPackage = packageName,
    )
}
