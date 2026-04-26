const fs = require('fs');
const path = require('path');

const base = path.join('c:', 'Mnzm', 'XianxiaSectNative', 'android', 'app', 'src', 'main', 'java', 'com', 'xianxia', 'sect');

function fixFile(filepath, description) {
    let content = fs.readFileSync(filepath, 'utf-8');
    const lines = content.split(/\r?\n/);
    const newLines = [];
    let skipNext = 0;
    
    for (let i = 0; i < lines.length; i++) {
        if (skipNext > 0) {
            skipNext--;
            continue;
        }
        
        const line = lines[i];
        
        // Skip _errorMessage declaration line
        if (/^\s*private val _errorMessage = MutableStateFlow/.test(line)) {
            // Also skip the next line (val errorMessage: StateFlow)
            skipNext = 1;
            continue;
        }
        
        // Skip _successMessage declaration line
        if (/^\s*private val _successMessage = MutableStateFlow/.test(line)) {
            // Also skip the next line (val successMessage: StateFlow)
            skipNext = 1;
            continue;
        }
        
        // Skip clearErrorMessage method (single line)
        if (/^\s*fun clearErrorMessage\(\) \{.*\}/.test(line)) {
            continue;
        }
        
        // Skip clearSuccessMessage method (single line)
        if (/^\s*fun clearSuccessMessage\(\) \{.*\}/.test(line)) {
            continue;
        }
        
        // Skip clearErrorMessage method (multi-line start)
        if (/^\s*fun clearErrorMessage\(\) \{/.test(line)) {
            // Skip until closing brace
            let j = i + 1;
            while (j < lines.length && !/^\s*\}/.test(lines[j])) {
                j++;
            }
            i = j; // skip to closing brace
            continue;
        }
        
        // Skip clearSuccessMessage method (multi-line start)
        if (/^\s*fun clearSuccessMessage\(\) \{/.test(line)) {
            let j = i + 1;
            while (j < lines.length && !/^\s*\}/.test(lines[j])) {
                j++;
            }
            i = j;
            continue;
        }
        
        // Skip standalone clearErrorMessage() call
        if (/^\s*clearErrorMessage\(\)\s*$/.test(line)) {
            continue;
        }
        
        // Skip standalone clearSuccessMessage() call
        if (/^\s*clearSuccessMessage\(\)\s*$/.test(line)) {
            continue;
        }
        
        // Replace _errorMessage.value = X with showError(X)
        let modified = line.replace(/_errorMessage\.value = (.+)/, (match, p1) => {
            return `showError(${p1.trim()})`;
        });
        
        // Replace _successMessage.value = X with showSuccess(X)
        modified = modified.replace(/_successMessage\.value = (.+)/, (match, p1) => {
            return `showSuccess(${p1.trim()})`;
        });
        
        newLines.push(modified);
    }
    
    // Clean up multiple blank lines
    let result = newLines.join('\n');
    result = result.replace(/\n{3,}/g, '\n\n');
    
    fs.writeFileSync(filepath, result, 'utf-8');
    console.log(`Fixed: ${description}`);
}

// Fix all ViewModel files
fixFile(path.join(base, 'ui', 'game', 'GameViewModel.kt'), 'GameViewModel.kt');
fixFile(path.join(base, 'ui', 'game', 'ProductionViewModel.kt'), 'ProductionViewModel.kt');
fixFile(path.join(base, 'ui', 'game', 'SaveLoadViewModel.kt'), 'SaveLoadViewModel.kt');

console.log('All done!');
