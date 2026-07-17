package com.dataease.migration.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellEscaperTest {

    @Test
    void quotesShellSingleQuotes() {
        assertEquals("'can'\"'\"'t'", ShellEscaper.quote("can't"));
    }

    @Test
    void quotesSqlIdentifierBackticks() {
        assertEquals("`data``ease`", ShellEscaper.sqlIdentifier("data`ease"));
    }
}
