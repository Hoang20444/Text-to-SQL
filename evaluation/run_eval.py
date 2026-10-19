#!/usr/bin/env python3
"""
ASSIGNED TO: Dev 3 (AI Pipeline & Benchmark Lead)
Evaluation Script to measure Execution Accuracy for Text-to-SQL Chinook Assistant.

Usage:
    python run_eval.py
"""

import json
import time
import requests
import psycopg2
import pandas as pd
from tabulate import tabulate

API_URL = "http://localhost:8080/api/v1/query"
DB_CONFIG = {
    "dbname": "chinook_warehouse",
    "user": "postgres",
    "password": "postgrespassword",
    "host": "localhost",
    "port": 5432
}

def load_benchmark():
    with open("benchmark_dataset.json", "r", encoding="utf-8") as f:
        return json.load(f)

def execute_on_db(conn, sql):
    try:
        df = pd.read_sql_query(sql, conn)
        # Normalize: round floats, convert all column names to lower
        df.columns = [str(c).lower() for c in df.columns]
        return df, None
    except Exception as e:
        return None, str(e)

def compare_dataframes(df_gold, df_pred):
    if df_gold is None or df_pred is None:
        return False
    if df_gold.shape != df_pred.shape:
        return False
    # Compare row counts and values loosely
    try:
        # Check if values match regardless of column names
        gold_values = sorted([tuple(x) for x in df_gold.values])
        pred_values = sorted([tuple(x) for x in df_pred.values])
        return str(gold_values) == str(pred_values)
    except Exception:
        return False

def main():
    questions = load_benchmark()
    print(f"Loaded {len(questions)} evaluation questions.")

    try:
        conn = psycopg2.connect(**DB_CONFIG)
    except Exception as e:
        print(f"Database connection error: {e}")
        print("Please ensure Docker container is running: docker compose up -d")
        return

    results = []
    correct_count = 0

    print("Running evaluation against backend API...\n")

    for q in questions:
        qid = q["id"]
        difficulty = q["difficulty"]
        question_text = q["question"]
        gold_sql = q["gold_sql"]

        print(f"[{qid}/{len(questions)}] Testing ({difficulty}): {question_text}")

        # Step 1: Call API
        try:
            resp = requests.post(API_URL, json={"question": question_text}, timeout=15)
            api_data = resp.json()
            pred_sql = api_data.get("generatedSql", "")
            status = api_data.get("status", "FAILED")
        except Exception as e:
            pred_sql = ""
            status = f"API_ERROR: {e}"

        # Step 2: Execute Gold SQL & Pred SQL on Postgres
        df_gold, gold_err = execute_on_db(conn, gold_sql)
        df_pred, pred_err = execute_on_db(conn, pred_sql) if pred_sql else (None, "No SQL")

        # Step 3: Compare results
        is_match = compare_dataframes(df_gold, df_pred)
        if is_match:
            correct_count += 1

        results.append({
            "ID": qid,
            "Difficulty": difficulty,
            "Question": question_text[:40] + "...",
            "Status": status,
            "Match": "✅ YES" if is_match else "❌ NO"
        })

    conn.close()

    accuracy = (correct_count / len(questions)) * 100
    print("\n" + "="*50)
    print(f"FINAL EXECUTION ACCURACY: {accuracy:.2f}% ({correct_count}/{len(questions)})")
    print("="*50)

    # Export to CSV for defense slide
    df_results = pd.DataFrame(results)
    df_results.to_csv("eval_results_report.csv", index=False, encoding="utf-8-sig")
    print("Saved report to eval_results_report.csv")

if __name__ == "__main__":
    main()
