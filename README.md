# AntiCopyPaster

AntiCopyPaster is a plugin for IntelliJ IDEA that tracks the copying and pasting carried out by the developer and
suggests extracting duplicates into a new method as soon as they are introduced in the code.

> **Warning**: Please note that AntiCopyPaster has evolved from its prototype stage into a fully implemented tool. We appreciate any feedback
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

## Aider

AntiCopyPaster integrates with [Aider](https://aider.chat) to provide clone detection, automated refactoring, and method name recommendations powered by large language models (LLMs). This integration allows developers to leverage advanced AI models for real-time feedback when handling code duplication.

---

### Installation

1. Make sure **Python 3.8 – 3.13** is installed on your system.  
2. Install Aider by running the following commands in your terminal:  
   ```bash
   python -m pip install aider-install
   aider-install
   ```  
   For more details, see the [official Aider installation guide](https://aider.chat/docs/install.html).  
3. Confirm installation with:  
   ```bash
   aider --version
   ```  

*Placeholder for installation screenshot:*  
`![Aider installation screenshot](images/aider-install.png)`

---

### Configuration in AntiCopyPaster

1. Open IntelliJ IDEA and go to **Settings → Tools → AntiCopyPaster**.  
2. In the section *“Extraction judgements should be performed by”*, select **Aider**.  
3. Open the **Aider Settings** panel and configure:  
   - **Aider Path**: the path to the `aider` binary. On terminal, use command:  
     ```bash
     which aider
     ```  
     might return `/Users/username/.local/bin/aider`.  
   - **LLM Provider**: choose the provider you want to connect with (e.g., OpenAI, Gemini, DeepSeek, Anthropic).  
   - **Model Selection**: specify the model you want to use (e.g., `gpt-4o`, `gemini-pro`).  
   - **API Key**: enter your provider’s API key.  
     - [OpenAI](https://platform.openai.com/api-keys)  
     - [Gemini](https://ai.google.dev/gemini-api/docs/api-key)  
     - [DeepSeek](https://api-docs.deepseek.com/)  
     - [Anthropic](https://docs.anthropic.com/en/api/admin-api/apikeys/get-api-key)  

---

### Using Aider for Refactoring

1. Select a code fragment in a Java file.  
2. Perform a **copy** and then **paste** operation.  
3. AntiCopyPaster will trigger Aider’s clone detection.  
   - If clones are detected, a confirmation window will appear.  
   - Click **Yes** to proceed.  
4. Aider will generate a refactored version of the code.  
   - A **side-by-side comparison** of the original and refactored code will be displayed.  
   - After a few seconds, you will be asked whether to apply the change.  
   - Selecting **Yes** will update the original code with the refactored version.  

---

### Using Aider for Naming Suggestions

1. In **AntiCopyPaster Settings**, set **Aider** as the *Name recommendation model*.  
   - (If Aider is already selected for extraction judgements, this is set automatically.)  
2. Configure how many naming suggestions Aider should generate.
3. Perform a copy-paste refactoring as usual.  
4. Aider will generate multiple method name suggestions.  
   - These will appear in a drop-down menu.  
   - Select your preferred name to apply it.  


---

By integrating Aider, AntiCopyPaster makes it easier to manage code clones while improving code readability and maintainability.


### How to cite?
Please, use the following bibtex entry:

AlOmar, Eman Abdullah, Jacob Ashkenas, Robert Feliciano, Matthew Angelakos, Dimitrios Haralamppopoulos, Xing Qian, Mohamed Wiem Mkaouer, and Ali Ouni. "AntiCopyPaster 3.0: Just-in-Time Clone Refactoring." ACM Transactions on Software Engineering and Methodology (2025).

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

AlOmar, Eman Abdullah, Benjamin Knobloch, Thomas Kain, Christopher Kalish, Mohamed Wiem Mkaouer, and Ali Ouni. "AntiCopyPaster 2.0: Whitebox just-in-time code duplicates extraction." In Proceedings of the 2024 IEEE/ACM 46th International Conference on Software Engineering: Companion Proceedings, pp. 84-88. 2024.

```tex
@inproceedings{alomar2024anticopypaster,
  title={AntiCopyPaster 2.0: Whitebox just-in-time code duplicates extraction},
  author={AlOmar, Eman Abdullah and Knobloch, Benjamin and Kain, Thomas and Kalish, Christopher and Mkaouer, Mohamed Wiem and Ouni, Ali},
  booktitle={Proceedings of the 2024 IEEE/ACM 46th International Conference on Software Engineering: Companion Proceedings},
  pages={84--88},
  year={2024}
}
```

AlOmar, Eman Abdullah, Anton Ivanov, Zarina Kurbatova, Yaroslav Golubev, Mohamed Wiem Mkaouer, Ali Ouni, Timofey Bryksin, Le Nguyen, Amit Kini, and Aditya Thakur. "AntiCopyPaster: extracting code duplicates as soon as they are introduced in the IDE." In Proceedings of the 37th IEEE/ACM International Conference on Automated Software Engineering, pp. 1-4. 2022.

```tex
@inproceedings{alomar2022anticopypaster,
  title={AntiCopyPaster: extracting code duplicates as soon as they are introduced in the IDE},
  author={AlOmar, Eman Abdullah and Ivanov, Anton and Kurbatova, Zarina and Golubev, Yaroslav and Mkaouer, Mohamed Wiem and Ouni, Ali and Bryksin, Timofey and Nguyen, Le and Kini, Amit and Thakur, Aditya},
  booktitle={Proceedings of the 37th IEEE/ACM International Conference on Automated Software Engineering},
  pages={1--4},
  year={2022}
}
```

## Troubleshooting Installation

If you encounter issues installing or running the tool, try upgrading pip and reinstalling aider-chat:

```bash
python -m pip install --upgrade pip
pip install --upgrade aider-chat
```
If you encounter an error similar to UnicodeEncodeError 'UnicodeEncodeError: 'charmap' codec can't encode character '\u2588' in position XX: character maps to <undefined>', it means that Windows terminal is not using UTF-8 encoding by default.
To fix it, follow these steps:

1. Open Command Prompt as Administrator
   -- Press Start, type cmd, right-click Command Prompt, and choose Run as administrator.
2. Run the following commands:
```bash
setx PYTHONIOENCODING utf-8
chcp 65001
```
3. Restart your Command Prompt and re-run Aider.

## Contacts

If you have any questions or propositions, do not hesitate to contact Eman Abdullah AlOmar at ealomar@stevens.edu.
