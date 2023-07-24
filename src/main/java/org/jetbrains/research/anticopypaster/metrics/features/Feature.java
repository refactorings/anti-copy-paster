package org.jetbrains.research.anticopypaster.metrics.features;

public enum Feature {
    //Meta-Features
    TotalLinesOfCode("TotalLinesOfCode", 0),
    TotalSymbols("TotalSymbols", 1),
    SymbolsPerLine("SymbolsPerLine", 2),
    Area("Area", 3),
    AreaPerLine("AreaPerLine", 4),

    //Coupling-Features
    TotalConnectivity("TotalConnectivity", 5),
    TotalConnectivityPerLine("TotalConnectivityPerLine", 6),
    FieldConnectivity("FieldConnectivity", 7),
    FieldConnectivityPerLine("FieldConnectivityPerLine", 8),
    MethodConnectivity("MethodConnectivity", 9),
    MethodConnectivityPerLine("MethodConnectivityPerLine", 10),

    //Method-Features
    MethodDeclarationLines("MethodDeclarationLines", 11),
    MethodDeclarationSymbols("MethodDeclarationSymbols", 12),
    MethodDeclarationSymbolsPerLine("MethodDeclarationSymbolsPerLine", 13),
    MethodDeclarationArea("MethodDeclarationArea", 14),
    MethodDeclarationAreaPerLine("MethodDeclarationDepthPerLine", 15),

    //Keyword-Features
    KeywordContinueTotalCount("KeywordContinueTotalCount", 16),
    KeywordContinueCountPerLine("KeywordContinueCountPerLine", 17),
    KeywordForTotalCount("KeywordForTotalCount", 18),
    KeywordForCountPerLine("KeywordForCountPerLine", 19),
    KeywordNewTotalCount("KeywordNewTotalCount", 20),
    KeywordNewCountPerLine("KeywordNewCountPerLine", 21),
    KeywordSwitchTotalCount("KeywordSwitchTotalCount", 22),
    KeywordSwitchCountPerLine("KeywordSwitchCountPerLine", 23),
    KeywordAssertTotalCount("KeywordAssertTotalCount", 24),
    KeywordAssertCountPerLine("KeywordAssertCountPerLine", 25),
    KeywordSynchronizedTotalCount("KeywordSynchronizedTotalCount", 26),
    KeywordSynchronizedCountPerLine("KeywordSynchronizedCountPerLine", 27),
    KeywordBooleanTotalCount("KeywordBooleanTotalCount", 28),
    KeywordBooleanCountPerLine("KeywordBooleanCountPerLine", 29),
    KeywordDoTotalCount("KeywordDoTotalCount", 30),
    KeywordDoCountPerLine("KeywordDoCountPerLine", 31),
    KeywordIfTotalCount("KeywordIfTotalCount", 32),
    KeywordIfCountPerLine("KeywordIfCountPerLine", 33),
    KeywordThisTotalCount("KeywordThisTotalCount", 34),
    KeywordThisCountPerLine("KeywordThisCountPerLine", 35),
    KeywordBreakTotalCount("KeywordBreakTotalCount", 36),
    KeywordBreakCountPerLine("KeywordBreakCountPerLine", 37),
    KeywordDoubleTotalCount("KeywordDoubleTotalCount", 38),
    KeywordDoubleCountPerLine("KeywordDoubleCountPerLine", 39),
    KeywordThrowTotalCount("KeywordThrowTotalCount", 40),
    KeywordThrowCountPerLine("KeywordThrowCountPerLine", 41),
    KeywordByteTotalCount("KeywordByteTotalCount", 42),
    KeywordByteCountPerLine("KeywordByteCountPerLine", 43),
    KeywordElseTotalCount("KeywordElseTotalCount", 44),
    KeywordElseCountPerLine("KeywordElseCountPerLine", 45),
    KeywordCaseTotalCount("KeywordCaseTotalCount", 46),
    KeywordCaseCountPerLine("KeywordCaseCountPerLine", 47),
    KeywordInstanceofTotalCount("KeywordInstanceofTotalCount", 48),
    KeywordInstanceofCountPerLine("KeywordInstanceofCountPerLine", 49),
    KeywordReturnTotalCount("KeywordReturnTotalCount", 50),
    KeywordReturnCountPerLine("KeywordReturnCountPerLine", 51),
    KeywordTransientTotalCount("KeywordTransientTotalCount", 52),
    KeywordTransientCountPerLine("KeywordTransientCountPerLine", 53),
    KeywordCatchTotalCount("KeywordCatchTotalCount", 54),
    KeywordCatchCountPerLine("KeywordCatchCountPerLine", 55),
    KeywordIntTotalCount("KeywordIntTotalCount", 56),
    KeywordIntCountPerLine("KeywordIntCountPerLine", 57),
    KeywordShortTotalCount("KeywordShortTotalCount", 58),
    KeywordShortCountPerLine("KeywordShortCountPerLine", 59),
    KeywordTryTotalCount("KeywordTryTotalCount", 60),
    KeywordTryCountPerLine("KeywordTryCountPerLine", 61),
    KeywordCharTotalCount("KeywordCharTotalCount", 62),
    KeywordCharCountPerLine("KeywordCharCountPerLine", 63),
    KeywordFinalTotalCount("KeywordFinalTotalCount", 64),
    KeywordFinalCountPerLine("KeywordFinalCountPerLine", 65),
    KeywordFinallyTotalCount("KeywordFinallyTotalCount", 66),
    KeywordFinallyCountPerLine("KeywordFinallyCountPerLine", 67),
    KeywordLongTotalCount("KeywordLongTotalCount", 68),
    KeywordLongCountPerLine("KeywordLongCountPerLine", 69),
    KeywordStrictfpTotalCount("KeywordStrictfpTotalCount", 70),
    KeywordStrictfpCountPerLine("KeywordStrictfpCountPerLine", 71),
    KeywordFloatTotalCount("KeywordFloatTotalCount", 72),
    KeywordFloatCountPerLine("KeywordFloatCountPerLine", 73),
    KeywordSuperTotalCount("KeywordSuperTotalCount", 74),
    KeywordSuperCountPerLine("KeywordSuperCountPerLine", 75),
    KeywordWhileTotalCount("KeywordWhileTotalCount", 76),
    KeywordWhileCountPerLine("KeywordWhileCountPerLine", 77);

    private final String name;
    private final int id;

    Feature(String name, int id) {
        this.name = name;
        this.id = id;
    }

    public static Feature fromId(int id) {
        return Feature.values()[id];
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        if (name.startsWith("Keyword") && name.endsWith("TotalCount")) {
            return "the total count of the " + name.substring(7, 7 + name.length() - "Keyword".length() - "TotalCount".length()) + " keyword";
        }

        if (name.startsWith("Keyword") && name.endsWith("CountPerLine")) {
            return "the average count of the " + name.substring(7, 7 + name.length() - "Keyword".length() - "CountPerLine".length()) + " keyword";
        }

        return switch (this) {
            case MethodDeclarationSymbols -> "the total size of the enclosing method in symbols";
            case MethodDeclarationSymbolsPerLine -> "the per-line-averaged size of the enclosing method in symbols";
            case MethodDeclarationArea -> "the total nesting area of the enclosing method";
            case MethodDeclarationAreaPerLine -> "the per-line-averaged nesting area of the enclosing method";
            case TotalSymbols -> "the total size of the code fragment in symbols";
            case SymbolsPerLine -> "the per-line-averaged size of the code fragment in symbols";
            case Area -> "the total nested area of the code fragment";
            case AreaPerLine -> "the per-line-averaged nested area of the code fragment";
            case TotalLinesOfCode -> "the total number of lines of code";
            case TotalConnectivity -> "the total coupling with the enclosing class";
            case TotalConnectivityPerLine -> "the average coupling with the enclosing class";
            case FieldConnectivity -> "the total coupling with the enclosing class by fields";
            case FieldConnectivityPerLine -> "the average coupling with the enclosing class by fields";
            case MethodConnectivity -> "the total coupling with the enclosing class by methods";
            case MethodConnectivityPerLine -> "the average coupling with the enclosing class by methods";
            default -> "";
        };
    }

    public int getId() {
        return id;
    }
}

