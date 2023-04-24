# AntiCopyPaster

AntiCopyPaster is a plugin for IntelliJ IDEA that tracks the copying and pasting carried out by the developer and suggests extracting duplicates into a new method as soon as they are introduced in the code.

**Important**: _Please note that AntiCopyPaster is a prototype and a work in progress. We would appreciate any feedback on the concept itself, as well as the implementation._

### How to install

AntiCopyPaster requires IntelliJ IDEA of version 2022.3 to work. To install the plugin:

1. Download the pre-built version of the plugin from [here](https://github.com/refactorings/anti-copy-paster/releases/tag/v1.0.0);
2. Open IntelliJ IDEA and go to `File`/`Settings`/`Plugins`;
3. Select the gear icon, and choose `Install Plugin from Disk...`;
4. Choose the downloaded ZIP archive;
5. Click `Apply`;
6. Restart the IDE.

### How it works

The plugin monitors the copying and pasting that takes place inside the IDE. As soon as a code fragment is pasted, the plugin checks if it introduces code duplication, and if it does, the plugin calculates a set of code metrics for it, and these metrics are compared against the currently selected metrics thresholds. If the chosen thresholds are surpassed, the plugin suggests the developer to perform the `Extract Method` refactoring and applies the refactoring if necessary.

The metric thresholds are set via the MetricsGatherer, which determines the metrics for every function outside of a test class. The median of these values is the medium setting, the lower quartile is the setting for low, and the upper quartile is the setting for high.

### Metrics Categories

The metrics categories can each be set to Off/Low/Medium/High. The math behind each of the categories is as follows.

Keywords: The number of Keywords in the pasted fragment.
Complexity: The total nesting area of the pasted fragment.
Size: The number of lines in the pasted fragment.

### Experiments

The tool validation and embedded models are available here: https://github.com/JetBrains-Research/extract-method-experiments.

### TensorFlow Model

The TensorFlow Model has been deactivated for this metrics based version of the plugin in order to make it smaller and more lightweight. In order to add back in the TensorFlowModel, the files and subdirectories in the TensorFlowModel directory need to be added to the `src/main/resources` folder.

In addition, the `TensorflowModel.java` and `TensorflowNativeLibraryLoader.java` need to be uncommented. If you intend to add these back in, you should check out [this repository](https://github.com/JetBrains-Research/anti-copy-paster) where that version of the plugin is kept up to date. This is to ensure that none of the files have been updated since they've been commented out.

## Contacts

If you have any questions or propositions, do not hesitate to contact Yaroslav Golubev at yaroslav.golubev@jetbrains.com.
