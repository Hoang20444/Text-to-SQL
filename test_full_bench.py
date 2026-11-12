import urllib.request, json, time

system_content = 'You are a Text-to-SQL generator. Output ONLY a valid JSON object matching {"sql": "string", "explanation": "string", "assumptions": [], "selectedMetrics": [], "selectedDimensions": [], "chartRecommendation": {"type": "table", "title": "string", "series": []}}. Keep explanation brief under 15 words.'
user_content = json.dumps({'question': 'Show 5 customers ordered by FirstName', 'sqlDialect': 'SQL_SERVER', 'semanticContext': {'tables': [{'name': 'Customer', 'columns': [{'name': 'CustomerId'}, {'name': 'FirstName'}, {'name': 'LastName'}]}], 'metrics': [], 'dimensions': []}})
body = json.dumps({
    'model': 'qwen2.5-coder:1.5b',
    'temperature': 0,
    'response_format': {'type': 'json_object'},
    'options': {'num_ctx': 2048, 'num_thread': 8, 'num_predict': 200},
    'messages': [{'role': 'system', 'content': system_content}, {'role': 'user', 'content': user_content}]
}).encode()

start = time.time()
req = urllib.request.Request('http://127.0.0.1:11434/v1/chat/completions', data=body, headers={'Content-Type': 'application/json'})
res = urllib.request.urlopen(req)
data = json.load(res)
dur = time.time() - start

print(f'Full SQL-GEN time: {dur:.2f}s')
print('Result JSON:', data['choices'][0]['message']['content'])

