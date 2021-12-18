package com.sachett.slang.slangc.symbol

class BoolSymbol(
    override val name: String,
    override val firstAppearedLine: Int,
    var value: Boolean = SymbolType.BOOL.defaultValue as Boolean
) : ISymbol {
    override val symbolType: SymbolType = SymbolType.BOOL

    override fun isSymbolType(symbolType: SymbolType): Boolean = symbolType == SymbolType.BOOL
}