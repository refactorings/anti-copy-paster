# AntiCopyPaster

AntiCopyPaster is a plugin for IntelliJ IDEA that tracks the copying and pasting carried out by the developer and
suggests extracting duplicates into a new method as soon as they are introduced in the code.

> **Warning**: Please note that AntiCopyPaster is a prototype and a work in progress. We appreciate any feedback
> on both the concept itself and its implementation.

## How To Install

AntiCopyPaster requires IntelliJ IDEA version 2024.1.7 to work. To install the plugin:

1. Download the pre-built version of the plugin from 
   [here](https://sourceforge.net/projects/anti-copy-paster/files/latest/download).
2. Open IntelliJ IDEA and go to `File`/`Settings`/`Plugins`.
3. Select the gear icon, and choose `Install Plugin from Disk...`.
4. Choose the downloaded ZIP archive.
5. Click `Apply`.
6. Restart the IDE.

## Technical Information

### How It Works

The plugin monitors the copying and pasting that takes place inside the IDE. As soon as a code fragment is pasted,
the plugin checks if it introduces code duplication. If it does, the plugin calculates a set of metrics for its code
and compares its metrics with the weighted average metric values of each file; how this average is weighted is
controlled by the sensitivity settings for each metric. If the fragment's metrics exceed these thresholds, the plugin
will suggest for the fragment to be refactored. If this suggestion is accepted by the user, a refactoring prompt will
trigger, allowing the user to view and edit the refactored fragment before replacing each instance of the fragment
one at a time (or all at once).

If the user has Aider selected to perform extraction judgements, when they copy and paste inside the IDE, the plugin
will send at least one notification to the user informing them that Aider is running clone detection on the file(s) they
have selected for analysis. If the user has selected "Current File," they will only receive one notification, and only
one prompt will be sent to their model of choice asking it to detect clones within the file. If they have "All Files in
Current Directory" or "Multiple Files" selected, they will receive as many notifications as files within their current
directory or as files they selected, respectively, and a prompt asking for their model of choice to detect clones will
be sent for each file. If clones are detected within a file, the user will receive a popup asking if they would like for
Aider to run code refactoring on the file. If the user chooses yes, they will receive a notification letting them know
that Aider is running code refactoring on the file, before eventually showing the changes in a diff viewer and asking
the user if they would like to apply the changes to their file. If they choose yes, the changes will be applied;
otherwise, the file will remain unchanged.

### Metric Categories

AntiCopyPaster analyzes code fragments by considering four main categories of heuristics:

* Keywords: The number and/or frequency of Java language keywords (ex. `class`, `static`, `void`, etc.) in a fragment.
* Coupling: The number and/or frequency of references made by the fragment to variables defined outside the fragment.
* Complexity: The total and/or average nesting (essentially, indentation) of a fragment.
* Size: The number of lines, characters, and/or average per-line characters in a fragment.

These categories can be individually configured further in the plugin's advanced settings menu.

### Experiments

The tool validation and embedded models are available here:
https://github.com/JetBrains-Research/extract-method-experiments.

### How to cite?
Please, use the following bibtex entry:

```tex
@article{alomar2025anticopypaster,
  title={AntiCopyPaster 3.0: Just-in-Time Clone Refactoring},
  author={AlOmar, Eman Abdullah and Ashkenas, Jacob and Feliciano, Robert and Angelakos, Matthew and Haralamppopoulos, 
  Dimitrios and Qian, Xing and Mkaouer, Mohamed Wiem and Ouni, Ali},
  journal={ACM Transactions on Software Engineering and Methodology},
  year={2025},
  publisher={ACM New York, NY}
}
```


## Contacts

If you have any questions or propositions, do not hesitate to contact Eman AlOmar at ealomar@stevens.edu.
