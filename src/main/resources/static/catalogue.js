(function initializeCatalogueQuestions() {
  'use strict';
  document.querySelectorAll('[data-catalogue-form]').forEach(function bindQuestions(form) {
    function update() {
      form.querySelectorAll('[data-catalogue-question]').forEach(function updateQuestion(question) {
        const input = question.querySelector('[name^="answer."]');
        const key = question.dataset.conditionKey;
        const source = key ? form.elements.namedItem('answer.' + key) : null;
        const visible = !key || (!source.disabled && source.value === question.dataset.conditionValue);
        question.hidden = !visible;
        input.disabled = !visible;
        input.required = visible && question.dataset.required === 'true';
        if (!visible) input.value = '';
      });
    }
    form.addEventListener('input', update);
    form.addEventListener('change', update);
    update();
  });
})();
