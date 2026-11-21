DATA STRUCTURES LAB - TURBO C++ PROGRAMS
========================================

All programs are written for Turbo C++ 3.0 (old-style headers
<iostream.h>, <conio.h>, void main(), clrscr(), getch()).
No STL / namespace is used, so they compile directly in Turbo C++.

 No.   File name                                   Program
 ----  ------------------------------------------  ---------------------------------
 1a    1a_List_ADT_Using_Arrays.cpp                List ADT using Arrays
 1b    1b_List_ADT_Using_Linked_List.cpp           List ADT using Linked List
 2a    2a_Stack_ADT_Using_Singly_Linked_List.cpp   Stack ADT using Singly Linked List
 2b    2b_Queue_ADT_Using_Singly_Linked_List.cpp   Queue ADT using Singly Linked List
 3     3_Infix_To_Postfix_Using_Stack_ADT.cpp      Infix to Postfix (Stack ADT) + evaluation
 4     4_Priority_Queue_ADT.cpp                    Priority Queue ADT
 5     5_Binary_Search_Tree.cpp                    Binary Search Tree
 6     6_AVL_Tree.cpp                              AVL Tree
 7     7_BFS_and_DFS_for_a_Given_Graph.cpp         BFS and DFS for a given Graph
 8     8_Linear_Search_and_Binary_Search.cpp       Linear Search & Binary Search
 9a    9a_Bubble_Sort_Method.cpp                   Bubble Sort
 9b    9b_Selection_Sort_Method.cpp                Selection Sort
 9c    9c_Insertion_Sort_Method.cpp                Insertion Sort
 9d    9d_Radix_Sort_Method.cpp                    Radix Sort

HOW TO RUN
----------
1. Copy this folder into your Turbo C++ folder (e.g. C:\TURBOC3\BIN).
2. Open Turbo C++ -> File -> Open -> select the program.
3. Press Alt+F9 to compile, Ctrl+F9 to run, Alt+F5 to see output.

NOTE: Turbo C++ runs in DOS (DOSBox), which shows long file names in
short 8.3 form, e.g. 1A_LIST~1.CPP.  The file still opens and runs
normally.  If you prefer, rename a file to a short name such as P1A.CPP.

SAMPLE INPUTS
-------------
 3  : ((2+3)*(5-1))      -> Postfix 23+51-*   Value 20
      (use single-digit numbers, no spaces)
 7  : vertices 4, edges 4, edges: 1 2, 1 3, 2 4, 3 4, start 1
      -> BFS 1 2 3 4   DFS 1 2 4 3
 8  : 10 20 30 45 50 57 68 73 88 95, search 50
      -> Index 4, Position 5
