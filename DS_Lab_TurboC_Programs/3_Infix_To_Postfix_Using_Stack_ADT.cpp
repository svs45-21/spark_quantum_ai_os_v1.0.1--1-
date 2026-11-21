/* Program 3 : Infix to Postfix expression (use Stack ADT)
               and evaluation of the postfix expression
   Compiler  : Turbo C++ 3.0
   Note      : Operands must be single digits / letters, e.g. ((2+3)*(5-1)) */
#include<iostream.h>
#include<conio.h>
#include<stdio.h>
#include<ctype.h>
#include<string.h>
#define SIZE 50

/* ---------- Character stack (for conversion) ---------- */
class CharStack
{
  char a[SIZE];
  int top;
 public:
  CharStack()        { top=-1; }
  int empty()        { return (top==-1); }
  void push(char c)  { if(top<SIZE-1) a[++top]=c; }
  char pop()         { if(top==-1) return '\0'; return a[top--]; }
  char peek()        { if(top==-1) return '\0'; return a[top]; }
};

/* ---------- Integer stack (for evaluation) ---------- */
class IntStack
{
  int a[SIZE];
  int top;
 public:
  IntStack()         { top=-1; }
  int empty()        { return (top==-1); }
  void push(int v)   { if(top<SIZE-1) a[++top]=v; }
  int pop()          { if(top==-1) return 0; return a[top--]; }
  int peek()         { if(top==-1) return 0; return a[top]; }
};

int precedence(char op)
{
  if(op=='+' || op=='-')
    return 1;
  if(op=='*' || op=='/')
    return 2;
  return 0;
}

void infixToPostfix(char infix[], char postfix[])
{
  CharStack s;
  int i, k=0;
  for(i=0; infix[i]!='\0'; i++)
  {
    if(isalnum(infix[i]))
      postfix[k++]=infix[i];
    else if(infix[i]=='(')
      s.push('(');
    else if(infix[i]==')')
    {
      while(!s.empty() && s.peek()!='(')
        postfix[k++]=s.pop();
      if(!s.empty())
        s.pop();              /* remove '(' */
    }
    else if(infix[i]=='+' || infix[i]=='-' || infix[i]=='*' || infix[i]=='/')
    {
      while(!s.empty() && precedence(s.peek())>=precedence(infix[i]))
        postfix[k++]=s.pop();
      s.push(infix[i]);
    }
  }
  while(!s.empty())
    postfix[k++]=s.pop();
  postfix[k]='\0';
}

int evalPostfix(char postfix[])
{
  IntStack s;
  int i, op1, op2;
  for(i=0; postfix[i]!='\0'; i++)
  {
    if(isdigit(postfix[i]))
      s.push(postfix[i]-'0');
    else
    {
      op2=s.pop();
      op1=s.pop();
      switch(postfix[i])
      {
        case '+': s.push(op1+op2); break;
        case '-': s.push(op1-op2); break;
        case '*': s.push(op1*op2); break;
        case '/': if(op2==0)
                  {
                    cout<<"Division by zero!\n";
                    s.push(0);
                  }
                  else
                    s.push(op1/op2);
                  break;
      }
    }
  }
  return s.pop();
}

void main()
{
  char infix[SIZE], postfix[SIZE];
  int i, onlyDigits=1;
  clrscr();
  cout<<"Enter infix expression: ";
  cin>>infix;
  infixToPostfix(infix, postfix);
  cout<<"Converted Postfix expression: "<<postfix<<endl;
  for(i=0; postfix[i]!='\0'; i++)
    if(isalpha(postfix[i]))
      onlyDigits=0;
  if(onlyDigits)
    cout<<"Evaluated Postfix expression value is: "<<evalPostfix(postfix)<<endl;
  else
    cout<<"(Expression has variables, so it cannot be evaluated)"<<endl;
  getch();
}
