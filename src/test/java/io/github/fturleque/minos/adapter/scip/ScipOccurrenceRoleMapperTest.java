package io.github.fturleque.minos.adapter.scip;

import io.github.fturleque.minos.domain.OccurrenceRole;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SymbolRole;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipOccurrenceRoleMapperTest {

    private final ScipOccurrenceRoleMapper mapper = new ScipOccurrenceRoleMapper();

    @Test
    void mapsCombinedImportAndReadRolesAsReference() {
        Occurrence occurrence = Occurrence.newBuilder()
                .setSymbol("scip . fixture . User#")
                .setSymbolRoles(SymbolRole.Import_VALUE | SymbolRole.ReadAccess_VALUE)
                .build();

        Set<OccurrenceRole> roles = mapper.map(occurrence);

        assertTrue(roles.containsAll(Set.of(
                OccurrenceRole.REFERENCE,
                OccurrenceRole.IMPORT,
                OccurrenceRole.READ
        )));
        assertEquals(3, roles.size());
    }

    @Test
    void mapsDefinitionAndTestWithoutAddingReference() {
        Occurrence occurrence = Occurrence.newBuilder()
                .setSymbol("scip . fixture . UserService#")
                .setSymbolRoles(SymbolRole.Definition_VALUE | SymbolRole.Test_VALUE)
                .build();

        Set<OccurrenceRole> roles = mapper.map(occurrence);

        assertTrue(roles.containsAll(Set.of(OccurrenceRole.DEFINITION, OccurrenceRole.TEST)));
        assertEquals(2, roles.size());
    }

    @Test
    void treatsUnflaggedSymbolOccurrenceAsReference() {
        Occurrence occurrence = Occurrence.newBuilder()
                .setSymbol("scip . fixture . UserRepository#")
                .build();

        assertEquals(Set.of(OccurrenceRole.REFERENCE), mapper.map(occurrence));
    }

    @Test
    void mapsEmptyOccurrenceToOther() {
        assertEquals(Set.of(OccurrenceRole.OTHER), mapper.map(Occurrence.getDefaultInstance()));
    }
}
