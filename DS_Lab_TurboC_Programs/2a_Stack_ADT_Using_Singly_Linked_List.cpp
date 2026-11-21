/* Program 2(a) : Stack ADT using Singly Linked List
   Compiler    : Turbo C++ 3.0                                     */
#include<iostream.h>
#include<conio.h>
#include<stdio.h>
#include<stdlib.h>

struct Node
{
  int data;
  struct Node *next;
};

struct Node *top=NULL;

void push(int val)
{
  struct Node *newnode=(struct Node*)malloc(sizeof(struct Node));
  newnode->data=val;
  newnode->next=top;
  top=newnode;
}

void pop()
{
  struct Node *temp;
  if(top==NULL)
    cout<<"Stack Underflow\n";
  else
  {
    cout<<"The popped element is "<<top->data<<endl;
    temp=top;
    top=top->next;
    free(temp);
  }
}

void display()
{
  struct Node *ptr;
  if(top==NULL)
    cout<<"Stack is empty";
  else
  {
    ptr=top;
    cout<<"Stack elements are: ";
    while(ptr!=NULL)
    {
      cout<<ptr->data<<" ";
      ptr=ptr->next;
    }
  }
  cout<<endl;
}

void main()
{
  int ch,val;
  clrscr();
  cout<<"** STACK OPERATIONS **\n";
  cout<<"1) Push in stack"<<endl;
  cout<<"2) Pop from stack"<<endl;
  cout<<"3) Display stack"<<endl;
  cout<<"4) Exit"<<endl;
  do
  {
    cout<<"Enter choice: ";
    cin>>ch;
    switch(ch)
    {
      case 1: cout<<"Enter value to be pushed: ";
              cin>>val;
              push(val);
              break;
      case 2: pop();
              break;
      case 3: display();
              break;
      case 4: cout<<"Exit"<<endl;
              break;
      default: cout<<"Invalid Choice"<<endl;
    }
  }while(ch!=4);
  getch();
}
