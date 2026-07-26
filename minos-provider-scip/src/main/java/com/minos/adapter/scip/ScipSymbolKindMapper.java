package com.minos.adapter.scip;

import com.minos.domain.SymbolKind;
import org.scip_code.scip.SymbolInformation;

/**
 * Réduit la taxonomie riche de SCIP vers la taxonomie commune MINOS.
 *
 * <p>La valeur SCIP d'origine reste disponible via la référence fournisseur ;
 * MINOS ne doit donc pas inventer une précision absente de sa taxonomie commune.</p>
 */
final class ScipSymbolKindMapper {

    private final ScipDescriptorKindMapper descriptorKindMapper = new ScipDescriptorKindMapper();

    SymbolKind map(SymbolInformation.Kind kind, String rawSymbol) {
        SymbolKind providerKind = map(kind);
        if (providerKind != SymbolKind.OTHER
                || kind != null && kind != SymbolInformation.Kind.UnspecifiedKind) {
            return providerKind;
        }
        return descriptorKindMapper.map(rawSymbol);
    }

    SymbolKind map(SymbolInformation.Kind kind) {
        if (kind == null) {
            return SymbolKind.OTHER;
        }

        return switch (kind) {
            case Class, SingletonClass -> SymbolKind.CLASS;
            case Interface -> SymbolKind.INTERFACE;
            case Struct -> SymbolKind.STRUCT;
            case Enum -> SymbolKind.ENUM;
            case Trait, Protocol, TypeClass, Mixin -> SymbolKind.TRAIT;
            case Method, AbstractMethod, StaticMethod, SingletonMethod,
                    ProtocolMethod, PureVirtualMethod, TraitMethod,
                    TypeClassMethod, MethodSpecification -> SymbolKind.METHOD;
            case Constructor -> SymbolKind.CONSTRUCTOR;
            case Function -> SymbolKind.FUNCTION;
            case Field, StaticField, StaticDataMember -> SymbolKind.FIELD;
            case Property, StaticProperty, Getter, Setter, Accessor -> SymbolKind.PROPERTY;
            case Variable, StaticVariable, Value -> SymbolKind.VARIABLE;
            case TypeAlias -> SymbolKind.TYPE_ALIAS;
            case Namespace -> SymbolKind.NAMESPACE;
            case Package, PackageObject -> SymbolKind.PACKAGE;
            default -> SymbolKind.OTHER;
        };
    }
}
