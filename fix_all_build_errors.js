const fs = require('fs');
const path = require('path');

const base = path.join('c:', 'Mnzm', 'XianxiaSectNative', 'android', 'app', 'src', 'main', 'java', 'com', 'xianxia', 'sect');

// ============================================================
// Fix 1: GameViewModel.kt - Change ViewModel to BaseViewModel, replace _errorMessage/_successMessage
// ============================================================
function fixGameViewModel() {
    const filepath = path.join(base, 'ui', 'game', 'GameViewModel.kt');
    let content = fs.readFileSync(filepath, 'utf-8');
    
    // Remove import androidx.lifecycle.ViewModel
    content = content.replace('import androidx.lifecycle.ViewModel\n', '');
    
    // Change ViewModel() to BaseViewModel()
    content = content.replace(') : ViewModel() {', ') : BaseViewModel() {');
    
    // Remove _errorMessage and errorMessage declarations
    content = content.replace(/\n    private val _errorMessage = MutableStateFlow<String\?>\(null\)\n    val errorMessage: StateFlow<String\?> = _errorMessage\.asStateFlow\(\)\n/g, '\n');
    
    // Remove _successMessage and successMessage declarations
    content = content.replace(/\n    private val _successMessage = MutableStateFlow<String\?>\(null\)\n    val successMessage: StateFlow<String\?> = _successMessage\.asStateFlow\(\)\n/g, '\n');
    
    // Remove single-line clearErrorMessage/clearSuccessMessage
    content = content.replace(/\n    fun clearErrorMessage\(\) \{ _errorMessage\.value = null \}\n/g, '\n');
    content = content.replace(/\n    fun clearSuccessMessage\(\) \{ _successMessage\.value = null \}\n/g, '\n');
    
    // Remove multi-line clearErrorMessage (self-recursive)
    content = content.replace(/\n    fun clearErrorMessage\(\) \{\n        clearErrorMessage\(\)\n    \}\n/g, '\n');
    content = content.replace(/\n    fun clearSuccessMessage\(\) \{\n        clearSuccessMessage\(\)\n    \}\n/g, '\n');
    
    // Replace _errorMessage.value = ... with showError(...)
    content = content.replace(/_errorMessage\.value = (.+)$/gm, (match, p1) => {
        return `showError(${p1.trim()})`;
    });
    
    // Replace _successMessage.value = ... with showSuccess(...)
    content = content.replace(/_successMessage\.value = (.+)$/gm, (match, p1) => {
        return `showSuccess(${p1.trim()})`;
    });
    
    // Remove standalone clearErrorMessage() and clearSuccessMessage() calls
    content = content.replace(/\n        clearErrorMessage\(\)\n/g, '\n');
    content = content.replace(/\n        clearSuccessMessage\(\)\n/g, '\n');
    
    // Clean up multiple blank lines
    content = content.replace(/\n{3,}/g, '\n\n');
    
    fs.writeFileSync(filepath, content, 'utf-8');
    console.log('Fixed GameViewModel.kt');
}

// ============================================================
// Fix 2: ProductionViewModel.kt - Same as GameViewModel
// ============================================================
function fixProductionViewModel() {
    const filepath = path.join(base, 'ui', 'game', 'ProductionViewModel.kt');
    let content = fs.readFileSync(filepath, 'utf-8');
    
    // Remove import androidx.lifecycle.ViewModel
    content = content.replace('import androidx.lifecycle.ViewModel\n', '');
    
    // Change ViewModel() to BaseViewModel()
    content = content.replace(') : ViewModel() {', ') : BaseViewModel() {');
    
    // Remove _errorMessage and errorMessage declarations
    content = content.replace(/\n    private val _errorMessage = MutableStateFlow<String\?>\(null\)\n    val errorMessage: StateFlow<String\?> = _errorMessage\.asStateFlow\(\)\n/g, '\n');
    
    // Remove _successMessage and successMessage declarations
    content = content.replace(/\n    private val _successMessage = MutableStateFlow<String\?>\(null\)\n    val successMessage: StateFlow<String\?> = _successMessage\.asStateFlow\(\)\n/g, '\n');
    
    // Remove single-line clearErrorMessage/clearSuccessMessage
    content = content.replace(/\n    fun clearErrorMessage\(\) \{ _errorMessage\.value = null \}\n/g, '\n');
    content = content.replace(/\n    fun clearSuccessMessage\(\) \{ _successMessage\.value = null \}\n/g, '\n');
    content = content.replace(/\n    fun clearErrorMessage\(\) \{  \}\n/g, '\n');
    content = content.replace(/\n    fun clearSuccessMessage\(\) \{  \}\n/g, '\n');
    
    // Remove multi-line clearErrorMessage
    content = content.replace(/\n    fun clearErrorMessage\(\) \{\n        _errorMessage\.value = null\n    \}\n/g, '\n');
    content = content.replace(/\n    fun clearSuccessMessage\(\) \{\n        _successMessage\.value = null\n    \}\n/g, '\n');
    
    // Replace _errorMessage.value = ... with showError(...)
    content = content.replace(/_errorMessage\.value = (.+)$/gm, (match, p1) => {
        return `showError(${p1.trim()})`;
    });
    
    // Replace _successMessage.value = ... with showSuccess(...)
    content = content.replace(/_successMessage\.value = (.+)$/gm, (match, p1) => {
        return `showSuccess(${p1.trim()})`;
    });
    
    // Remove standalone clearErrorMessage() and clearSuccessMessage() calls
    content = content.replace(/\n        clearErrorMessage\(\)\n/g, '\n');
    content = content.replace(/\n        clearSuccessMessage\(\)\n/g, '\n');
    
    // Clean up multiple blank lines
    content = content.replace(/\n{3,}/g, '\n\n');
    
    fs.writeFileSync(filepath, content, 'utf-8');
    console.log('Fixed ProductionViewModel.kt');
}

// ============================================================
// Fix 3: SaveLoadViewModel.kt - Same as GameViewModel
// ============================================================
function fixSaveLoadViewModel() {
    const filepath = path.join(base, 'ui', 'game', 'SaveLoadViewModel.kt');
    let content = fs.readFileSync(filepath, 'utf-8');
    
    // Remove import androidx.lifecycle.ViewModel
    content = content.replace('import androidx.lifecycle.ViewModel\n', '');
    
    // Change ViewModel() to BaseViewModel()
    content = content.replace(') : ViewModel() {', ') : BaseViewModel() {');
    
    // Remove _errorMessage and errorMessage declarations
    content = content.replace(/\n    private val _errorMessage = MutableStateFlow<String\?>\(null\)\n    val errorMessage: StateFlow<String\?> = _errorMessage\.asStateFlow\(\)\n/g, '\n');
    
    // Remove _successMessage and successMessage declarations
    content = content.replace(/\n    private val _successMessage = MutableStateFlow<String\?>\(null\)\n    val successMessage: StateFlow<String\?> = _successMessage\.asStateFlow\(\)\n/g, '\n');
    
    // Remove single-line clearErrorMessage/clearSuccessMessage
    content = content.replace(/\n    fun clearErrorMessage\(\) \{ _errorMessage\.value = null \}\n/g, '\n');
    content = content.replace(/\n    fun clearSuccessMessage\(\) \{ _successMessage\.value = null \}\n/g, '\n');
    content = content.replace(/\n    fun clearErrorMessage\(\) \{  \}\n/g, '\n');
    content = content.replace(/\n    fun clearSuccessMessage\(\) \{  \}\n/g, '\n');
    
    // Remove multi-line clearErrorMessage
    content = content.replace(/\n    fun clearErrorMessage\(\) \{\n        _errorMessage\.value = null\n    \}\n/g, '\n');
    content = content.replace(/\n    fun clearSuccessMessage\(\) \{\n        _successMessage\.value = null\n    \}\n/g, '\n');
    
    // Replace _errorMessage.value = ... with showError(...)
    content = content.replace(/_errorMessage\.value = (.+)$/gm, (match, p1) => {
        return `showError(${p1.trim()})`;
    });
    
    // Replace _successMessage.value = ... with showSuccess(...)
    content = content.replace(/_successMessage\.value = (.+)$/gm, (match, p1) => {
        return `showSuccess(${p1.trim()})`;
    });
    
    // Remove standalone clearErrorMessage() and clearSuccessMessage() calls
    content = content.replace(/\n        clearErrorMessage\(\)\n/g, '\n');
    content = content.replace(/\n        clearSuccessMessage\(\)\n/g, '\n');
    
    // Clean up multiple blank lines
    content = content.replace(/\n{3,}/g, '\n\n');
    
    fs.writeFileSync(filepath, content, 'utf-8');
    console.log('Fixed SaveLoadViewModel.kt');
}

// ============================================================
// Fix 4: GameActivity.kt - Replace errorMessage usage with errorEvents
// ============================================================
function fixGameActivity() {
    const filepath = path.join(base, 'ui', 'game', 'GameActivity.kt');
    let content = fs.readFileSync(filepath, 'utf-8');
    
    // Replace errorMessage collection
    content = content.replace(
        'val errorMessage by saveLoadViewModel.errorMessage.collectAsState()',
        'var errorMessage by remember { mutableStateOf<String?>(null) }'
    );
    
    // Add LaunchedEffect for errorEvents after the existing LaunchedEffect for gameData.sectName
    const afterLaunchedEffect = `                    LaunchedEffect(gameData.sectName) {
                        if (gameData.sectName.isNotEmpty()) {
                            isInitialLoading.value = false
                        }
                    }`;
    const withErrorEvents = `                    LaunchedEffect(gameData.sectName) {
                        if (gameData.sectName.isNotEmpty()) {
                            isInitialLoading.value = false
                        }
                    }

                    LaunchedEffect(Unit) {
                        saveLoadViewModel.errorEvents.collect { error ->
                            errorMessage = error
                        }
                    }`;
    content = content.replace(afterLaunchedEffect, withErrorEvents);
    
    // Replace clearErrorMessage() calls
    content = content.replace(/saveLoadViewModel\.clearErrorMessage\(\)/g, 'errorMessage = null');
    
    fs.writeFileSync(filepath, content, 'utf-8');
    console.log('Fixed GameActivity.kt');
}

// ============================================================
// Fix 5: AppError.kt - Fix return type mismatch
// ============================================================
function fixAppError() {
    const filepath = path.join(base, 'core', 'util', 'AppError.kt');
    let content = fs.readFileSync(filepath, 'utf-8');
    
    // Change return type from AppError.GameLoop to AppError
    content = content.replace(
        'fun GameLoopError.toAppError(): AppError.GameLoop = when (this) {',
        'fun GameLoopError.toAppError(): AppError = when (this) {'
    );
    
    fs.writeFileSync(filepath, content, 'utf-8');
    console.log('Fixed AppError.kt');
}

// ============================================================
// Fix 6: UiError.kt - Fix unresolved StorageError reference
// ============================================================
function fixUiError() {
    const filepath = path.join(base, 'core', 'util', 'UiError.kt');
    let content = fs.readFileSync(filepath, 'utf-8');
    
    // Replace StorageError with fully qualified name
    content = content.replace(
        'fun fromStorageError(error: StorageError, message: String = ""): UiError =',
        'fun fromStorageError(error: com.xianxia.sect.data.result.StorageError, message: String = ""): UiError ='
    );
    
    fs.writeFileSync(filepath, content, 'utf-8');
    console.log('Fixed UiError.kt');
}

// Run all fixes
fixGameViewModel();
fixProductionViewModel();
fixSaveLoadViewModel();
fixGameActivity();
fixAppError();
fixUiError();

console.log('\nAll fixes applied!');
