package models.features.feature;

public enum Feature {
    TotalConnectivity("TotalConnectivity", 0),
    TotalConnectivityPerLine("TotalConnectivityPerLine", 1),
    FieldConnectivity("FieldConnectivity", 2),
    FieldConnectivityPerLine("FieldConnectivityPerLine", 3),
    MethodConnectivity("MethodConnectivity", 4),
    MethodConnectivityPerLine("MethodConnectivityPerLine", 5),
    KeywordAbstractTotalCount("KeywordAbstractTotalCount", 6),
    KeywordAbstractCountPerLine("KeywordAbstractCountPerLine", 7),
    KeywordContinueTotalCount("KeywordContinueTotalCount", 8),
    KeywordContinueCountPerLine("KeywordContinueCountPerLine", 9),
    KeywordForTotalCount("KeywordForTotalCount", 10),
    KeywordForCountPerLine("KeywordForCountPerLine", 11),
    KeywordNewTotalCount("KeywordNewTotalCount", 12),
    KeywordNewCountPerLine("KeywordNewCountPerLine", 13),
    KeywordSwitchTotalCount("KeywordSwitchTotalCount", 14),
    KeywordSwitchCountPerLine("KeywordSwitchCountPerLine", 15),
    KeywordAssertTotalCount("KeywordAssertTotalCount", 16),
    KeywordAssertCountPerLine("KeywordAssertCountPerLine", 17),
    KeywordDefaultTotalCount("KeywordDefaultTotalCount", 18),
    KeywordDefaultCountPerLine("KeywordDefaultCountPerLine", 19),
    KeywordPackageTotalCount("KeywordPackageTotalCount", 20),
    KeywordPackageCountPerLine("KeywordPackageCountPerLine", 21),
    KeywordSynchronizedTotalCount("KeywordSynchronizedTotalCount", 22),
    KeywordSynchronizedCountPerLine("KeywordSynchronizedCountPerLine", 23),
    KeywordBooleanTotalCount("KeywordBooleanTotalCount", 24),
    KeywordBooleanCountPerLine("KeywordBooleanCountPerLine", 25),
    KeywordDoTotalCount("KeywordDoTotalCount", 26),
    KeywordDoCountPerLine("KeywordDoCountPerLine", 27),
    KeywordIfTotalCount("KeywordIfTotalCount", 28),
    KeywordIfCountPerLine("KeywordIfCountPerLine", 29),
    KeywordPrivateTotalCount("KeywordPrivateTotalCount", 30),
    KeywordPrivateCountPerLine("KeywordPrivateCountPerLine", 31),
    KeywordThisTotalCount("KeywordThisTotalCount", 32),
    KeywordThisCountPerLine("KeywordThisCountPerLine", 33),
    KeywordBreakTotalCount("KeywordBreakTotalCount", 34),
    KeywordBreakCountPerLine("KeywordBreakCountPerLine", 35),
    KeywordDoubleTotalCount("KeywordDoubleTotalCount", 36),
    KeywordDoubleCountPerLine("KeywordDoubleCountPerLine", 37),
    KeywordImplementsTotalCount("KeywordImplementsTotalCount", 38),
    KeywordImplementsCountPerLine("KeywordImplementsCountPerLine", 39),
    KeywordProtectedTotalCount("KeywordProtectedTotalCount", 40),
    KeywordProtectedCountPerLine("KeywordProtectedCountPerLine", 41),
    KeywordThrowTotalCount("KeywordThrowTotalCount", 42),
    KeywordThrowCountPerLine("KeywordThrowCountPerLine", 43),
    KeywordByteTotalCount("KeywordByteTotalCount", 44),
    KeywordByteCountPerLine("KeywordByteCountPerLine", 45),
    KeywordElseTotalCount("KeywordElseTotalCount", 46),
    KeywordElseCountPerLine("KeywordElseCountPerLine", 47),
    KeywordImportTotalCount("KeywordImportTotalCount", 48),
    KeywordImportCountPerLine("KeywordImportCountPerLine", 49),
    KeywordPublicTotalCount("KeywordPublicTotalCount", 50),
    KeywordPublicCountPerLine("KeywordPublicCountPerLine", 51),
    KeywordThrowsTotalCount("KeywordThrowsTotalCount", 52),
    KeywordThrowsCountPerLine("KeywordThrowsCountPerLine", 53),
    KeywordCaseTotalCount("KeywordCaseTotalCount", 54),
    KeywordCaseCountPerLine("KeywordCaseCountPerLine", 55),
    KeywordEnumTotalCount("KeywordEnumTotalCount", 56),
    KeywordEnumCountPerLine("KeywordEnumCountPerLine", 57),
    KeywordInstanceofTotalCount("KeywordInstanceofTotalCount", 58),
    KeywordInstanceofCountPerLine("KeywordInstanceofCountPerLine", 59),
    KeywordReturnTotalCount("KeywordReturnTotalCount", 60),
    KeywordReturnCountPerLine("KeywordReturnCountPerLine", 61),
    KeywordTransientTotalCount("KeywordTransientTotalCount", 62),
    KeywordTransientCountPerLine("KeywordTransientCountPerLine", 63),
    KeywordCatchTotalCount("KeywordCatchTotalCount", 64),
    KeywordCatchCountPerLine("KeywordCatchCountPerLine", 65),
    KeywordExtendsTotalCount("KeywordExtendsTotalCount", 66),
    KeywordExtendsCountPerLine("KeywordExtendsCountPerLine", 67),
    KeywordIntTotalCount("KeywordIntTotalCount", 68),
    KeywordIntCountPerLine("KeywordIntCountPerLine", 69),
    KeywordShortTotalCount("KeywordShortTotalCount", 70),
    KeywordShortCountPerLine("KeywordShortCountPerLine", 71),
    KeywordTryTotalCount("KeywordTryTotalCount", 72),
    KeywordTryCountPerLine("KeywordTryCountPerLine", 73),
    KeywordCharTotalCount("KeywordCharTotalCount", 74),
    KeywordCharCountPerLine("KeywordCharCountPerLine", 75),
    KeywordFinalTotalCount("KeywordFinalTotalCount", 76),
    KeywordFinalCountPerLine("KeywordFinalCountPerLine", 77),
    KeywordInterfaceTotalCount("KeywordInterfaceTotalCount", 78),
    KeywordInterfaceCountPerLine("KeywordInterfaceCountPerLine", 79),
    KeywordStaticTotalCount("KeywordStaticTotalCount", 80),
    KeywordStaticCountPerLine("KeywordStaticCountPerLine", 81),
    KeywordVoidTotalCount("KeywordVoidTotalCount", 82),
    KeywordVoidCountPerLine("KeywordVoidCountPerLine", 83),
    KeywordClassTotalCount("KeywordClassTotalCount", 84),
    KeywordClassCountPerLine("KeywordClassCountPerLine", 85),
    KeywordFinallyTotalCount("KeywordFinallyTotalCount", 86),
    KeywordFinallyCountPerLine("KeywordFinallyCountPerLine", 87),
    KeywordLongTotalCount("KeywordLongTotalCount", 88),
    KeywordLongCountPerLine("KeywordLongCountPerLine", 89),
    KeywordStrictfpTotalCount("KeywordStrictfpTotalCount", 90),
    KeywordStrictfpCountPerLine("KeywordStrictfpCountPerLine", 91),
    KeywordVolatileTotalCount("KeywordVolatileTotalCount", 92),
    KeywordVolatileCountPerLine("KeywordVolatileCountPerLine", 93),
    KeywordConstTotalCount("KeywordConstTotalCount", 94),
    KeywordConstCountPerLine("KeywordConstCountPerLine", 95),
    KeywordFloatTotalCount("KeywordFloatTotalCount", 96),
    KeywordFloatCountPerLine("KeywordFloatCountPerLine", 97),
    KeywordNativeTotalCount("KeywordNativeTotalCount", 98),
    KeywordNativeCountPerLine("KeywordNativeCountPerLine", 99),
    KeywordSuperTotalCount("KeywordSuperTotalCount", 100),
    KeywordSuperCountPerLine("KeywordSuperCountPerLine", 101),
    KeywordWhileTotalCount("KeywordWhileTotalCount", 102),
    KeywordWhileCountPerLine("KeywordWhileCountPerLine", 103),
    TotalSymbolsInCodeFragment("TotalSymbolsInCodeFragment", 104),
    AverageSymbolsInCodeLine("AverageSymbolsInCodeLine", 105),
    TotalLinesDepth("TotalLinesDepth", 106),
    AverageLinesDepth("AverageLinesDepth", 107),
    TotalCommitsInFragment("TotalCommitsInFragment", 108),
    TotalAuthorsInFragment("TotalAuthorsInFragment", 109),
    LiveTimeOfFragment("LiveTimeOfFragment", 110),
    AverageLiveTimeOfLine("AverageLiveTimeOfLine", 111),
    TotalLinesOfCode("TotalLinesOfCode", 112),
    MethodDeclarationSymbols("MethodDeclarationSymbols", 113),
    MethodDeclarationAverageSymbols("MethodDeclarationAverageSymbols", 114),
    MethodDeclarationDepth("MethodDeclarationDepth", 115),
    MethodDeclarationDepthPerLine("MethodDeclarationDepthPerLine", 116);

    Feature(String name, int id) {
        this.name = name;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public String getCyrName() {
        if (name.startsWith("Keyword") && name.endsWith("TotalCount")) {
            return name.substring(7, 7 + name.length() - "Keyword".length() - "TotalCount".length()) + " keywords";
        }

        if (name.startsWith("Keyword") && name.endsWith("CountPerLine")) {
            return name.substring(7, 7 + name.length() - "Keyword".length() - "CountPerLine".length()) + " keywords on average";
        }

        switch (this) {
            case MethodDeclarationSymbols:
                return "total size of the method";
            case MethodDeclarationAverageSymbols:
                return "average size of the method";
            case MethodDeclarationDepth:
                return "total depth factor of the method";
            case MethodDeclarationDepthPerLine:
                return "average depth factor of the method";
            case TotalSymbolsInCodeFragment:
                return "total size of the code fragment";
            case AverageSymbolsInCodeLine:
                return "average size of the code fragment";
            case TotalLinesDepth:
                return "total nested depth of the method";
            case AverageLinesDepth:
                return "average nested depth of the method";
            case TotalLinesOfCode:
                return "total lines of code";
            case TotalConnectivity:
                return "total coupling with the class";
            case TotalConnectivityPerLine:
                return "average coupling with the class";
            case FieldConnectivity:
                return "total coupling with the class by fields";
            case FieldConnectivityPerLine:
                return "average coupling with the class by fields";
            case MethodConnectivity:
                return "total coupling with the class by methods";
            case MethodConnectivityPerLine:
                return "average coupling with the class by methods";
            default:
                return "";
        }
    }

    public int getId() {
        return id;
    }

    private String name;
    private int id;

    public static Feature fromId(int id) {
        return Feature.values()[id];
    }
}

