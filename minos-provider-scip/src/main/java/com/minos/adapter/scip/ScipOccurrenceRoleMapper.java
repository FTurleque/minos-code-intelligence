package com.minos.adapter.scip;

import com.minos.domain.OccurrenceRole;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SymbolRole;

import java.util.EnumSet;
import java.util.Set;

/**
 * Convertit le bitset {@code SymbolRole} de SCIP vers les rôles MINOS.
 */
final class ScipOccurrenceRoleMapper {

    Set<OccurrenceRole> map(Occurrence occurrence) {
        int roles = occurrence.getSymbolRoles();
        EnumSet<OccurrenceRole> result = EnumSet.noneOf(OccurrenceRole.class);

        addIfSet(roles, SymbolRole.Definition_VALUE, OccurrenceRole.DEFINITION, result);
        addIfSet(roles, SymbolRole.ForwardDefinition_VALUE, OccurrenceRole.FORWARD_DEFINITION, result);
        addIfSet(roles, SymbolRole.Import_VALUE, OccurrenceRole.IMPORT, result);
        addIfSet(roles, SymbolRole.WriteAccess_VALUE, OccurrenceRole.WRITE, result);
        addIfSet(roles, SymbolRole.ReadAccess_VALUE, OccurrenceRole.READ, result);
        addIfSet(roles, SymbolRole.Generated_VALUE, OccurrenceRole.GENERATED, result);
        addIfSet(roles, SymbolRole.Test_VALUE, OccurrenceRole.TEST, result);

        boolean definition = result.contains(OccurrenceRole.DEFINITION)
                || result.contains(OccurrenceRole.FORWARD_DEFINITION);

        if (!definition && !occurrence.getSymbol().isBlank()) {
            result.add(OccurrenceRole.REFERENCE);
        }

        if (result.isEmpty()) {
            result.add(OccurrenceRole.OTHER);
        }

        return Set.copyOf(result);
    }

    private void addIfSet(
            int bitset,
            int bit,
            OccurrenceRole role,
            EnumSet<OccurrenceRole> target) {
        if ((bitset & bit) != 0) {
            target.add(role);
        }
    }
}
